/* Generated By:JJTree: Do not edit this line. ASTString.java */

package com.bigdata.rdf.sail.sparql.ast;

import com.bigdata.rdf.sail.sparql.ast.SimpleNode;
import com.bigdata.rdf.sail.sparql.ast.SyntaxTreeBuilder;
import com.bigdata.rdf.sail.sparql.ast.SyntaxTreeBuilderVisitor;
import com.bigdata.rdf.sail.sparql.ast.VisitorException;

public class ASTString extends SimpleNode {

	private String value;

	public ASTString(int id) {
		super(id);
	}

	public ASTString(SyntaxTreeBuilder p, int id) {
		super(p, id);
	}

	@Override
	public Object jjtAccept(SyntaxTreeBuilderVisitor visitor, Object data)
		throws VisitorException
	{
		return visitor.visit(this, data);
	}

	public String getValue() {
		return value;
	}

	public void setValue(String value) {
		this.value = value;
	}

	@Override
	public String toString()
	{
		return super.toString() + " (" + value + ")";
	}
}