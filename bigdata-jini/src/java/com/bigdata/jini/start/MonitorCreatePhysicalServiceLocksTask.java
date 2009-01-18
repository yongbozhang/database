package com.bigdata.jini.start;

import java.net.UnknownHostException;
import java.nio.channels.FileLock;
import java.rmi.RemoteException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import net.jini.core.entry.Entry;
import net.jini.core.lookup.ServiceID;
import net.jini.core.lookup.ServiceRegistrar;
import net.jini.core.lookup.ServiceTemplate;

import org.apache.log4j.Logger;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import com.bigdata.io.SerializerUtil;
import com.bigdata.jini.lookup.entry.Hostname;
import com.bigdata.jini.lookup.entry.ServiceUUID;
import com.bigdata.jini.start.config.AbstractHostConstraint;
import com.bigdata.jini.start.config.IServiceConstraint;
import com.bigdata.jini.start.config.ManagedServiceConfiguration;
import com.bigdata.jini.start.config.ServiceConfiguration;
import com.bigdata.service.jini.JiniFederation;
import com.bigdata.service.jini.JiniUtil;
import com.bigdata.zookeeper.UnknownChildrenWatcher;
import com.bigdata.zookeeper.ZLock;
import com.bigdata.zookeeper.ZNodeLockWatcher;

/**
 * This task notices when a new lock node is created and creates and runs a
 * {@link CreatePhysicalServiceTask} to handle that lock node. The lock node
 * represents a specific logical service instance which is short at least one
 * physical service instance. The data of the lock node contains the zpath of
 * the logical service instance.
 * <p>
 * Note: A single instance of this task is started by
 * {@link AbstractServicesManagerService#start()}. Further, this task
 * serializes service creations events using a {@link #lock}. If only a single
 * {@link ServicesManagerServer} is run per host, then this allows
 * {@link IServiceConstraint}s to be specified that restrict the mixture of
 * services running a host.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class MonitorCreatePhysicalServiceLocksTask implements
        Callable<Void> {

    final static protected Logger log = Logger
            .getLogger(MonitorCreatePhysicalServiceLocksTask.class);

    final static protected boolean INFO = log.isInfoEnabled();

    final static protected boolean DEBUG = log.isDebugEnabled();

    private final JiniFederation fed;
    
    private final ZooKeeper zookeeper;
    
    private final IServiceListener listener;
    
    /**
     * Used to serialize physical service creates on a given host. This is
     * important in order for constaints on the maximum #of service instances on
     * a single host to be respected (and assumes only a single services manager
     * per host).
     * <p>
     * Consider: A new {@link ServicesManagerServer} starts on a host. It sees a
     * demand for 10 data services. For each one, it tests a constraint on the
     * maximum #of data service instances (LTE 2) and finds that it has NO data
     * services running, so for each one it creates a data service and then it
     * has 10. By synchronizing here we force this to single thread the service
     * creates and in order to avoid this problem.
     * <p>
     * Note: this assumes one {@link MonitorCreatePhysicalServiceLocksTask} per
     * {@link ServicesManagerServer}, which should be true. See the startup
     * logic in {@link AbstractServicesManagerService}.
     * <p>
     * Note: This assumes that there is one {@link ServicesManagerServer} per
     * host. We are not enforcing that constraint.
     * <p>
     * Note: This is also used to serialize service restarts. This prevents the
     * possiblity of a service restart for a service which is concurrently
     * starting. See {@link RestartPersistentServices} and
     * {@link #restartPhysicalService(ManagedServiceConfiguration, String, String, Entry[])}
     */
    final protected ReentrantLock lock = new ReentrantLock();
    
    public MonitorCreatePhysicalServiceLocksTask(final JiniFederation fed,
            final IServiceListener listener) {

        if (fed == null)
            throw new IllegalArgumentException();
        
        if (listener == null)
            throw new IllegalArgumentException();

        this.fed = fed;
        
        this.listener = listener;
        
        this.zookeeper = fed.getZookeeper();

    }

    /**
     * Start monitoring the {@link BigdataZooDefs#LOCKS_CREATE_PHYSICAL_SERVICE}
     * znode.
     * <p>
     * Note: If the znode does not exist or {@link ZooKeeper} is not connected,
     * then the task will keep trying to establish it watch until the znode is
     * created.
     * <p>
     * Note: This task runs until cancelled.
     */
    public Void call() throws Exception {

        /*
         * All the locks of interest are direct children of this znode.
         */
        final String locksZPath = fed.getZooConfig().zroot + "/"
                + BigdataZooDefs.LOCKS_CREATE_PHYSICAL_SERVICE;

        /*
         * Note: The UnknownChildrenWatcher will keep trying until it is
         * able to establish the watch. 
         */
        final UnknownChildrenWatcher watcher = new UnknownChildrenWatcher(
                zookeeper, locksZPath);
     
        try {

            // consume new lock nodes from the watcher's queue.
            while (true) {

                try {

                    // child znode.
                    final String znode = watcher.queue.take();

                    if (znode.endsWith(ZNodeLockWatcher.INVALID)) {

                        /*
                         * This is not a lock node. It is a marker indicating
                         * that the corresponding lock node is about to be
                         * destroyed. We just ignore it here.
                         */

                        continue;

                    }

                    // path to the new lock node.
                    final String lockNodeZPath = locksZPath + "/" + znode;

                    if (INFO)
                        log.info("new lock: zpath=" + lockNodeZPath);

                    /*
                     * Note: This task should run until either it or another
                     * services manager causes the service to be created, until
                     * the lock node is destroyed, or until the constraints no
                     * longer indicate that we need to create a new service
                     * instance.
                     */

                    fed.submitMonitoredTask(new CreatePhysicalServiceTask(
                            lockNodeZPath));

                } catch (InterruptedException ex) {

                    // exit on interrupt (task cancelled)

                    log.warn("Interrupted.");

                    throw ex;

                } catch (Throwable t) {

                    /*
                     * Continue processing if there are errors since we still
                     * want to monitor for new service start requests.
                     */

                    log.error(this, t);

                }

            }
            
        } finally {

            watcher.cancel();

        }

    }

    /**
     * Task contends for the {@link ZLock}. If the lock is obtained, the
     * {@link ServiceConfiguration} is fetched using the zpath written into the
     * data of the lock node and the service constraints are checked. If the
     * constraints are satisified by this host, then the task attempts to start
     * the service. If the service can be started successfully, then the lock
     * node is destroyed and the task exits. Otherwise the task releases the
     * lock and sleeps a bit. Either it or another task running on another
     * {@link ServicesManagerServer} will gain the lock and try again. (If the
     * lock node is invalidated or destroyed, then the task will quit.)
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public class CreatePhysicalServiceTask implements Callable<Boolean> {

        /**
         * The zpath of the lock node. The data in this node is the xpath of the
         * logical service whose instance needs to be created.
         */
        protected final String lockNodeZPath;

        public CreatePhysicalServiceTask(final String lockNodeZPath) {

            if (lockNodeZPath == null)
                throw new IllegalArgumentException();

            this.lockNodeZPath = lockNodeZPath;

        }

        /**
         * Contends for the {@link ZLock} and then invokes
         * {@link #runWithZLock()}, which will verify the constraints and
         * attempt to start the service.
         * <p>
         * Note: If we are unable to create the service while we are holding the
         * lock then we wait a little bit and try again. This covers the case
         * where there are preconditions for the service start which have not
         * been met but which might become satisified at any time.
         * <p>
         * Note: If the lock node is deleted, then we will exit and return
         * <code>false</code>.
         * 
         * @param lockNodeZPath
         *            The path to the lock node.
         * 
         * @return <code>true</code> if we started the service.
         * 
         * @throws Exception
         *             if we could not start the service.
         */
        public Boolean call() throws Exception {

            // until we create the service or the lock node is destroyed.
            while (true) {

                try {
                    
                    if (runOnce()) {

                        return true;

                    }
                    
                } catch (InterruptedException ex) {

                    // interrupted - stop competing.
                    throw ex;

                } catch (Throwable t) {

                    log.warn("lockNode=" + lockNodeZPath, t);

                    // fall through.

                }
                
                /*
                 * We did not create the service this time. This can occur if
                 * there is a precondition for the service which is not
                 * satisified. Wait a bit a try again.
                 * 
                 * Note: We have released the both the local [lock] and the
                 * [zlock] so other service creates can proceed while we sleep
                 * on this one.
                 * 
                 * @todo configure retry interval.
                 */

                Thread.sleep(5000/* ms */);

                if (INFO)
                    log.info("Retrying: " + lockNodeZPath);

            } // while true

        }

        /**
         * Run once, trying to acquire the {@link ZLock}.
         * 
         * @return <code>true</code> iff the service was created.
         * 
         * @throws Exception
         */
        private boolean runOnce() throws Exception {

            if (zookeeper.exists(lockNodeZPath, false) == null) {

                /*
                 * End of the competition. Either someone created the service or
                 * someone destroyed the lock node.
                 */

                throw new InterruptedException("lock node is gone: zpath="
                        + lockNodeZPath);

            }

            // enter the competition.
            final ZLock zlock = ZNodeLockWatcher.getLock(zookeeper,
                    lockNodeZPath, fed.getZooConfig().acl);

            /*
             * Wait for the global lock.
             * 
             * Note: It SHOULD be Ok to wait forever here. The process holding
             * the zlock is represented by an ephemeral znode. If it dies the
             * lock will be released.
             * 
             * Note: Blocking here DOES NOT prevent the services manager from
             * contending for zlocks for other service types in parallel.
             */
            zlock.lock();
            try {

                /*
                 * We are holding a global lock on the right to create a
                 * physical service instance for the logical service whose zpath
                 * is given by the data in the lock node.
                 */
                
                if (INFO)
                    log.info("have lock: zpath=" + lockNodeZPath);
                
                if (runWithZLock()) {

                    // iff successful, then destroy the lock.
                    zlock.destroyLock();

                    // end of the competition.
                    return true;

                }

                return false;
                
            } finally {

                zlock.unlock();

            }

        }
        
        /**
         * Either barges in or waits at most a short while before yielding to
         * another process (by returning false).
         * <p>
         * Note: We are using nexted locks (a global {@link ZLock} and a local
         * {@link #lock}). The global {@link ZLock} allows at most one process
         * to proceed per logical service. The local {@link #lock} allows only
         * one task to create a service at a time on a given host (well, a given
         * services manager). This barge-in / timeout pattern prevents other
         * distributed processes from blocking while this process is seeking to
         * acquire a local lock. Either it will grab the lock immediately or
         * wait at most a short interval for the lock.
         * 
         * @return <code>true</code> iff the service was started.
         * 
         * @throws Exception
         */
        private boolean runWithZLock()
                throws Exception {

            /*
             * Either barge-in or wait for up to short interval.
             * 
             * @todo configure this interval?
             */
            if (lock.tryLock() || lock.tryLock(2000, TimeUnit.MILLISECONDS)) {

                try {

                    return runWithLocalLock();

                } finally {

                    lock.unlock();

                }

            }
            
            return false;

        }

        private boolean runWithLocalLock() throws Exception {
            
            return checkConstraintsAndStartService();
            
        }

        /**
         * Starts the service if the the {@link IServiceConstraint}s are
         * satisified.
         * <p>
         * Note: This fetches the {@link ServiceConfiguration} and tests the
         * {@link IServiceConstraint}s after we hold the {@link ZLock} and then
         * makes the decision whether or not to start the service. That way it
         * judges matters as they stand at the time of the decision, rather than
         * when we joined the queue to contend for the {@link ZLock}.
         * 
         * @throws Exception
         */
        private boolean checkConstraintsAndStartService() throws Exception {

            /*
             * Note: The data is the logicalService zpath.
             */
            final String logicalServiceZPath = (String) SerializerUtil
                    .deserialize(zookeeper.getData(lockNodeZPath, false,
                            new Stat()));

            /*
             * If we hack off the last path component, we now have the zpath for the
             * ServiceConfiguration znode.
             */
            final String serviceConfigZPath = logicalServiceZPath.substring(0,
                    logicalServiceZPath.lastIndexOf('/'));

            if (INFO)
                log.info("logicalServiceZPath=" + logicalServiceZPath);

            final ManagedServiceConfiguration config = (ManagedServiceConfiguration) SerializerUtil
                    .deserialize(zookeeper.getData(serviceConfigZPath, false,
                            new Stat()));

            if (INFO)
                log.info("Considering: " + config);

            if (!config.canStartService(fed)) {

                // will not start this service.

                if (INFO)
                    log.info("Constraint(s) do not allow service start: "
                            + config);

                return false;

            }

            // get children (the list of physical services).
            final List<String> children = zookeeper
                    .getChildren(logicalServiceZPath + "/"
                            + BigdataZooDefs.PHYSICAL_SERVICES_CONTAINER, false);

            if (INFO)
                log.info("serviceConfigZPath=" + serviceConfigZPath
                        + ", logicalServiceZPath=" + logicalServiceZPath
                        + ", targetReplicationCount=" + config.replicationCount
                        + ", #children=" + children.size() + ", children="
                        + children);

            final int ninstances = children.size();
            
            if (ninstances >= config.replicationCount) {

                /*
                 * Stops the task from running if the #of physical service
                 * instances is GTE the target replicationCount.
                 */

                throw new InterruptedException("No new instances required.");

            } else {

                /*
                 * There are not enough physical service instances so try to
                 * start one now.
                 */

                // start the service.
                startService(config, logicalServiceZPath);

                return true;

            }

        }
        
        /**
         * Start the service.
         * 
         * @param config
         *            The service configuration.
         * @param logicalServiceZPath
         *            The zpath of the logical service.
         * 
         * @throws TimeoutException
         *             if the service did not start within the configured
         *             timeout.
         * 
         * @throws Exception
         */
        @SuppressWarnings("unchecked")
        protected void startService(final ManagedServiceConfiguration config,
                final String logicalServiceZPath) throws Exception {

            if (INFO)
                log.info("config=" + config + ", zpath=" + logicalServiceZPath);

            /*
             * Create task to start the service.
             * 
             * Note: We do not specify the service attributes because this is a
             * service start (vs a service restart).
             */
            final Callable task = config.newServiceStarter(fed, listener,
                    logicalServiceZPath, null/* attributes */);

            /*
             * Submit the task and waits for its Future (up to the timeout).
             */
            fed.getExecutorService().submit(task).get(config.timeout,
                    TimeUnit.MILLISECONDS);

        }

    }

    /**
     * Restarts the service if it is not running.
     * <p>
     * There is a small window within which a new service which is starting
     * could qualify for restart. The physical service znode is created by the
     * service itself. We depend on the existence of that znode to identify
     * services for restart. If the physical service creates the ephemeral znode
     * in the {@link BigdataZooDefs#MASTER_ELECTION} container after it creates
     * its physical service znode and before it is registered with a
     * {@link ServiceRegistrar} that we are also joined with then the service
     * would qualify for "restart".
     * <p>
     * If {@link FileLock} is supported for the OS and the volume on which the
     * serviceDir lives (NFS does not support FileLock), then the service
     * obtains a {@link FileLock} and concurrent starts will be disallowed (an
     * exception will be thrown by the process which does not get the
     * {@link FileLock}). Where supported, {@link FileLock} will prevent the
     * service from being started manually if it is already running.
     * <p>
     * The window is closed for automatic restarts by internally acquiring the
     * same {@link #lock} that is used to serialize service starts.
     * 
     * @param serviceConfig
     * @param logicalServiceZPath
     * @param physicalServiceZPath
     * @param attributes
     * 
     * @return <code>true</code> iff the service is known to have been
     *         restarted. <code>false</code> will be returned if a timeout
     *         occurred while attempting to restart the service.
     * 
     * @throws InterruptedException
     *             if interrupted while awaiting the {@link #lock} or while
     *             re-starting the service.
     */
    protected boolean restartIfNotRunning(
            final ManagedServiceConfiguration serviceConfig,
            final String logicalServiceZPath,
            final String physicalServiceZPath, final Entry[] attributes)
            throws InterruptedException {

        try {

            if (!isLocalService(attributes)) {

                return false;

            }

        } catch (UnknownHostException ex) {

            log.warn("className=" + serviceConfig.className
                    + ", physicalServiceZPath=" + physicalServiceZPath, ex);

            return false;
            
        }

        if (INFO)
            log.info("Service is local: className=" + serviceConfig
                        + ", physicalServiceZPath=" + physicalServiceZPath);

        // block until we can evaluate this service for restart.
        lock.lockInterruptibly();
        try {

            final boolean shouldRestart;

            try {

                shouldRestart = shouldRestartPhysicalService(serviceConfig,
                        logicalServiceZPath, physicalServiceZPath, attributes);

            } catch (RemoteException ex) {

                log.error("className=" + serviceConfig
                        + ", physicalServiceZPath=" + physicalServiceZPath, ex);

                return false;

            }

            if (!shouldRestart) {
                
                if (INFO)
                    log.info("Service is running: className=" + serviceConfig
                            + ", physicalServiceZPath=" + physicalServiceZPath);
                
                return false;
                
            }

            if (!serviceConfig.canStartService(fed)) {

                log
                        .warn("Service not running : start prevented by constraints: className="
                                + serviceConfig
                                + ", physicalServiceZPath="
                                + physicalServiceZPath);

                return false;
                
            }
            
            // Restart the service.
            try {

                return restartPhysicalService(serviceConfig, logicalServiceZPath,
                    physicalServiceZPath, attributes);

            } catch(InterruptedException t) {

                log.error("Service restart interrupted: className="
                        + serviceConfig + ", physicalServiceZPath="
                        + physicalServiceZPath);

                // rethrow so that the caller will see the interrupt.
                throw t;
                
            } catch(Throwable t) {
                
                // log and ignore.
                log.error("Service restart error: className=" + serviceConfig
                        + ", physicalServiceZPath=" + physicalServiceZPath);
            
                // service did not start (or at least within the timeout).
                return false;
                
            }

        } finally {

            lock.unlock();

        }
        
    }

    /**
     * Figure out if the service lives on this host.
     * 
     * @return <code>true</code> if the service lives on this host.
     * 
     * @throws UnknownHostException
     */
    protected boolean isLocalService(final Entry[] attributes)
            throws UnknownHostException {

        boolean isLocalHost = false;

        for (Entry e : attributes) {

            if (e instanceof Hostname) {

                final String hostname = ((Hostname) e).hostname;

                if (AbstractHostConstraint.isLocalHost(hostname)) {

                    isLocalHost = true;

                }

            }

        }

        return isLocalHost;

    }
    
    /**
     * Consider a physical service for restart. The service must be persistent
     * (its znode is persistent), it must have been started on the local host,
     * it must be disconnected from zookeeper (we verify that it does not have a
     * znode in the {@link BigdataZooDefs#MASTER_ELECTION} container), and it
     * must not be discoverable using jini.
     * 
     * @param serviceConfig
     * @param logicalServiceZPath
     * @param physicalServiceZPath
     * @param attributes
     * 
     * @throws RemoteException
     *             if there was an RMI problem with jini.
     * @throws IllegalMonitorStateException
     *             if the current thread does not hold the {@link #lock}.
     */
    private boolean shouldRestartPhysicalService(
            final ManagedServiceConfiguration serviceConfig,
            final String logicalServiceZPath,
            final String physicalServiceZPath, final Entry[] attributes)
            throws RemoteException {

        if(!lock.isHeldByCurrentThread())
            throw new IllegalMonitorStateException();

        if (INFO)
            log.info("Considering: className=" + serviceConfig.className
                    + ", physicalServiceZPath=" + physicalServiceZPath);
        
        /*
         * Extract the serviceID and figure out if the service lives on this
         * host.
         */
        final ServiceID serviceID;
        {

            UUID serviceUUID = null;

            for (Entry e : attributes) {

                if (e instanceof ServiceUUID) {

                    serviceUUID = ((ServiceUUID) e).serviceUUID;

                    break;

                }

            }

            if (serviceUUID == null) {

                log.error("No ServiceUUID? className="
                        + serviceConfig.className + ", physicalServiceZPath="
                        + physicalServiceZPath);

                return false;

            }

            serviceID = JiniUtil.uuid2ServiceID(serviceUUID);

        }

        /*
         * Check to see if the service is discoverable. If it is then we will
         * not restart it.
         */
        
        final ServiceRegistrar[] serviceRegistrars = fed
                .getDiscoveryManagement().getRegistrars();

        if (serviceRegistrars == null) {

            // we can't continue without a service registrar.
            throw new RuntimeException("No service registrars.");

        }

        for (ServiceRegistrar reg : serviceRegistrars) {

            if (reg.lookup(new ServiceTemplate(serviceID, null/* iface[] */,
                    null/* attributes */)) != null) {

                if (INFO)
                    log.info("Service discoverable: className="
                            + serviceConfig.className
                            + ", physicalServiceZPath=" + physicalServiceZPath);

                return false;
                
            }

        }

        /*
         * Check to see if the service is known to zookeeper.
         */

        return true;
        
    }

    /**
     * @param serviceConfig
     * @param logicalServiceZPath
     * @param physicalServiceZPath
     * @param attributes
     * 
     * @throws Exception
     *             if the task to start the service could not be created.
     * @throws TimeoutException
     *             if the service does not start within the
     *             {@link ServiceConfiguration#timeout}
     * @throws ExecutionException
     *             if the task starting the service fails.
     * @throws InterruptedException
     *             if the task starting the service is interrupted.
     * @throws IllegalMonitorStateException
     *             if the current thread does not hold the {@link #lock}.
     */
    @SuppressWarnings("unchecked")
    protected boolean restartPhysicalService(
            final ManagedServiceConfiguration serviceConfig,
            final String logicalServiceZPath,
            final String physicalServiceZPath, final Entry[] attributes)
            throws InterruptedException, ExecutionException, TimeoutException,
            Exception {

        if(!lock.isHeldByCurrentThread())
            throw new IllegalMonitorStateException();
        
        if (INFO)
            log.info("config=" + serviceConfig + ", zpath="
                    + logicalServiceZPath);

        /*
         * Create task to start the service.
         * 
         * Note: We do not specify the service attributes because this is a
         * service start (vs a service restart).
         */
        final Callable task = serviceConfig.newServiceStarter(fed, listener,
                logicalServiceZPath, null/* attributes */);

        /*
         * Submit the task and waits for its Future (up to the timeout).
         */
        fed.getExecutorService().submit(task).get(serviceConfig.timeout,
                TimeUnit.MILLISECONDS);
        
        return true;

    }

}
