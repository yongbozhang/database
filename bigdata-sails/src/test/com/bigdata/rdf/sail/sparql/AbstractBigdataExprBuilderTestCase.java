/**

Copyright (C) SYSTAP, LLC 2006-2011.  All rights reserved.

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
/*
 * Created on Aug 21, 2011
 */

package com.bigdata.rdf.sail.sparql;

import java.util.Properties;

import junit.framework.TestCase;

import org.apache.log4j.Logger;
import org.openrdf.query.MalformedQueryException;

import com.bigdata.bop.BOp;
import com.bigdata.bop.engine.AbstractQueryEngineTestCase;
import com.bigdata.journal.BufferMode;
import com.bigdata.journal.ITx;
import com.bigdata.journal.Journal;
import com.bigdata.rdf.axioms.NoAxioms;
import com.bigdata.rdf.internal.IV;
import com.bigdata.rdf.internal.VTE;
import com.bigdata.rdf.internal.impl.TermId;
import com.bigdata.rdf.model.BigdataValue;
import com.bigdata.rdf.model.BigdataValueFactory;
import com.bigdata.rdf.sail.sparql.ast.Node;
import com.bigdata.rdf.sail.sparql.ast.SimpleNode;
import com.bigdata.rdf.sparql.ast.IQueryNode;
import com.bigdata.rdf.sparql.ast.QueryRoot;
import com.bigdata.rdf.sparql.ast.UpdateRoot;
import com.bigdata.rdf.sparql.ast.VarNode;
import com.bigdata.rdf.store.AbstractTripleStore;
import com.bigdata.rdf.store.LocalTripleStore;
import com.bigdata.rdf.vocab.NoVocabulary;

/**
 * Abstract base class for tests of the {@link BigdataExprBuilder} and friends.
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class AbstractBigdataExprBuilderTestCase extends TestCase {

    private static final Logger log = Logger
            .getLogger(TestBigdataExprBuilder.class);

    /**
     * 
     */
    public AbstractBigdataExprBuilderTestCase() {
    }

    /**
     * @param name
     */
    public AbstractBigdataExprBuilderTestCase(String name) {
        super(name);
    }

    protected String baseURI = null;
    
    /*
     * TODO All of these fields can be moved into the [context] field.
     */
    
    protected AbstractTripleStore tripleStore = null;

    protected String lex = null;// The namespace of the LexiconRelation.

    protected BigdataValueFactory valueFactory = null;

    protected BigdataASTContext context;

    protected void setUp() throws Exception {
        
        tripleStore = getStore(getProperties());

        lex = tripleStore.getLexiconRelation().getNamespace();
        
        valueFactory = tripleStore.getValueFactory();
        
        this.context = new BigdataASTContext(tripleStore);
        
    }

    protected void tearDown() throws Exception {
        
        baseURI = null;

        if (tripleStore != null) {
            
            tripleStore.__tearDownUnitTest();
            
            tripleStore = null;
            
        }
        
        lex = null;
        
        valueFactory = null;
        
        context = null;
        
    }

    /**
     * Return an anonymous variable having exactly the given variable name.
     * <p>
     * Note: This avoids a side-effect on the counter, which is part of the
     * {@link BigdataASTContext} against which the parse tree is being
     * interpreted.
     * 
     * @param varname
     *            The exact variable name (NOT a prefix).
     * 
     * @return The anonymous variable.
     */
    protected VarNode mockAnonVar(final String varname) {
        
        final VarNode var = new VarNode(varname);
        
        var.setAnonymous(true);
        
        return var;
        
    }
    
    /**
     * Note: makeIV() in the tests can leave the IV as a MockIV since we will
     * never have anything in the database (unless there is a Vocabulary or it
     * is otherwise inline, in which case this code is sufficient to resolve the
     * inline IV).
     * 
     * @see {@link BatchRDFValueResolver}.
     */
    @SuppressWarnings("unchecked")
    protected IV<BigdataValue, ?> makeIV(final BigdataValue value) {

        @SuppressWarnings("rawtypes")
        IV iv = tripleStore.getLexiconRelation().getInlineIV(value);

        if (iv == null) {

            iv = (IV<BigdataValue, ?>) TermId.mockIV(VTE.valueOf(value));

            iv.setValue(value);

        }

        return iv;

    }
    
    /**
     * Marks the variable as anonymous.
     * 
     * @param v
     *            The variable.
     *            
     * @return The argument.
     */
    protected VarNode makeAnon(final VarNode v) {
        v.setAnonymous(true);
        return v;
    }

    /*
     * Mock up the tripleStore.
     */
    
    protected Properties getProperties() {

        final Properties properties = new Properties();

//        // turn on quads.
//        properties.setProperty(AbstractTripleStore.Options.QUADS, "true");

        // override the default vocabulary.
        properties.setProperty(AbstractTripleStore.Options.VOCABULARY_CLASS,
                NoVocabulary.class.getName());

        // turn off axioms.
        properties.setProperty(AbstractTripleStore.Options.AXIOMS_CLASS,
                NoAxioms.class.getName());

        // Note: No persistence.
        properties.setProperty(com.bigdata.journal.Options.BUFFER_MODE,
                BufferMode.Transient.toString());
        
        return properties;

    }

    protected AbstractTripleStore getStore(final Properties properties) {

        final String namespace = "kb";

        // create/re-open journal.
        final Journal journal = new Journal(properties);

        final LocalTripleStore lts = new LocalTripleStore(journal, namespace,
                ITx.UNISOLATED, properties);

        lts.create();

        return lts;

    }

    /**
     * Applies the {@link Bigdata2ASTSPARQLParser}.
     */
    protected QueryRoot parse(final String queryStr, final String baseURI)
            throws MalformedQueryException {

        return new Bigdata2ASTSPARQLParser(tripleStore).parseQuery2(queryStr,
                baseURI).getOriginalAST();

    }

    /**
     * Applies the {@link Bigdata2ASTSPARQLParser}.
     */
    protected UpdateRoot parseUpdate(final String updateStr, final String baseURI)
            throws MalformedQueryException {

        return new Bigdata2ASTSPARQLParser(tripleStore).parseUpdate2(updateStr,
                baseURI).getOriginalUpdateAST();

    }

    protected static void assertSameAST(final String queryStr,
            final IQueryNode expected, final IQueryNode actual) {

        // Get a reference to the parse tree.
        Node parseTree = null;
        
        if (expected instanceof QueryRoot) {

//            if (((QueryRoot) expected).getQueryString() == null) {
//                /*
//                 * Note: Discard the query string since the unit tests are not
//                 * setting that attribute at this time.
//                 */
//                ((QueryRoot) actual).setQueryString(null);
//            }
//        
//            // Grab a reference to the parse tree.
//            parseTree = (Node) ((QueryRoot) actual).getParseTree();
//            
//            // Clear parse tree annotation since it is not on [expected].
//            ((QueryRoot) actual).setParseTree(null);

            if (((QueryRoot) expected).getQueryHints() == null) {
                /*
                 * Note: Discard the query hints since the unit tests are not
                 * building those up from the AST at this time.
                 */
                ((QueryRoot) actual).setQueryHints(null);
            }
            
            if (((QueryRoot) expected).getDataset() == null) {
                /*
                 * Note: Discard the data set since the unit tests are not
                 * building those up from the AST at this time.
                 */
                ((QueryRoot) actual).setDataset(null);
            }
            
        }

        if (!expected.equals(actual)) {

            log.error("\nqueryStr:\n" + queryStr);
            log.error("\nparseTree:\n"
                    + (parseTree == null ? null : ((SimpleNode) parseTree)
                            .dump("")));
            log.error("\nexpected:\n" + expected);
            log.error("\nactual:\n" + actual);

            AbstractQueryEngineTestCase.diff((BOp) expected, (BOp) actual);

            // No difference was detected?
            throw new AssertionError();

        }
        
    }

}
