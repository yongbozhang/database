/**

Copyright (C) SYSTAP, LLC 2006-2010.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package com.bigdata.journal.jini.ha;

import com.bigdata.journal.BufferMode;

/**
 * FIXME HAWORM: This test suite is not implemented. It needs to override the
 * {@link BufferMode}.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 */
public class TestHA3WORMJournalServer extends TestHA3JournalServer {

    public TestHA3WORMJournalServer() {
    }

    public TestHA3WORMJournalServer(String nme) {
        super(nme);
    }

    protected BufferMode getDiskMode() {
        return BufferMode.DiskWORM;
    }

}
