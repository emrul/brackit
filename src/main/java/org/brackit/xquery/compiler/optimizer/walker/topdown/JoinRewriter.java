/*
 * [New BSD License]
 * Copyright (c) 2011-2012, Brackit Project Team <info@brackit.org>  
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above copyright
 *       notice, this list of conditions and the following disclaimer in the
 *       documentation and/or other materials provided with the distribution.
 *     * Neither the name of the Brackit Project Team nor the
 *       names of its contributors may be used to endorse or promote products
 *       derived from this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.brackit.xquery.compiler.optimizer.walker.topdown;

import static org.brackit.xquery.compiler.XQ.ComparisonExpr;
import static org.brackit.xquery.compiler.XQ.GeneralCompGE;
import static org.brackit.xquery.compiler.XQ.GeneralCompGT;
import static org.brackit.xquery.compiler.XQ.GeneralCompLE;
import static org.brackit.xquery.compiler.XQ.GeneralCompLT;
import static org.brackit.xquery.compiler.XQ.GeneralCompNE;
import static org.brackit.xquery.compiler.XQ.NodeCompFollows;
import static org.brackit.xquery.compiler.XQ.NodeCompIs;
import static org.brackit.xquery.compiler.XQ.NodeCompPrecedes;
import static org.brackit.xquery.compiler.XQ.Selection;
import static org.brackit.xquery.compiler.XQ.ValueCompGE;
import static org.brackit.xquery.compiler.XQ.ValueCompGT;
import static org.brackit.xquery.compiler.XQ.ValueCompLE;
import static org.brackit.xquery.compiler.XQ.ValueCompLT;
import static org.brackit.xquery.compiler.XQ.ValueCompNE;

import java.util.Arrays;

import org.brackit.xquery.compiler.AST;

/**
 * @author Sebastian Baechle
 * 
 */
public class JoinRewriter extends ScopeWalker {

	@Override
	protected AST visit(AST select) {
		if (select.getType() != Selection) {
			return select;
		}
		AST predicate = select.getChild(0);

		if (predicate.getType() != ComparisonExpr) {
			return select;
		}
		AST comparison = predicate.getChild(0);

		switch (comparison.getType()) {
		case NodeCompFollows:
		case NodeCompIs:
		case NodeCompPrecedes:
		case GeneralCompNE:
		case ValueCompNE:
			return select;
		}

		// left side must not be static
		AST s1Expr = predicate.getChild(1);
		VarRef s1VarRefs = findVarRefs(s1Expr);
		if (s1VarRefs == null) {
			return select;
		}
		// right side must not be static
		AST s2Expr = predicate.getChild(2);
		VarRef s2VarRefs = findVarRefs(s2Expr);
		if (s2VarRefs == null) {
			return select;
		}
		// extract scopes of referenced variables
		Scope[] s1Scopes = sortedScopes(s1VarRefs);
		Scope[] s2Scopes = sortedScopes(s2VarRefs);

		if (s2Scopes[s2Scopes.length - 1]
				.compareTo(s1Scopes[s1Scopes.length - 1]) < 0) {
			// swap left and right in comparison
			AST tmpAst = s1Expr;
			s1Expr = s2Expr;
			s2Expr = tmpAst;
			VarRef tmpMinVarRef = s1VarRefs;
			s1VarRefs = s2VarRefs;
			s2VarRefs = tmpMinVarRef;
			Scope[] tmpScopes = s1Scopes;
			s1Scopes = s2Scopes;
			s2Scopes = tmpScopes;
			comparison = swapCmp(comparison);
		}

		// S1 and S2 may overlap
		// => trim overlapping scopes, i.e., S0
		int s1Pos = 0;
		int s2Pos = 0;
		Scope s0End = null;
		Scope s1Begin = s1Scopes[s1Pos];
		Scope s2Begin = s2Scopes[s2Pos];
		while (true) {
			if ((s1Begin.compareTo(s2Begin) >= 0)) {
				if (++s1Pos == s1Scopes.length) {
					// S1 is empty
					return select;
				}
				s0End = s1Begin;
				s1Begin = s1Scopes[s1Pos];
			} else if ((s0End != null) && ((s2Begin.compareTo(s0End) >= 0))) {
				if (++s2Pos == s2Scopes.length) {
					// S2 is empty
					return select;
				}
				s0End = s2Begin;
				s2Begin = s1Scopes[s1Pos];
			} else {
				break;
			}
		}

		return select;
	}

	/*
	 * create a sorted and duplicate-free array of accessed scopes
	 */
	private Scope[] sortedScopes(VarRef varRefs) {
		int cnt = 0;
		for (VarRef ref = varRefs; ref != null; ref = ref.next) {
			cnt++;
		}
		int pos = 0;
		Scope[] tmp = new Scope[cnt];
		for (VarRef ref = varRefs; ref != null; ref = ref.next) {
			tmp[pos++] = ref.referredScope;
		}
		pos = 0;
		Scope p = tmp[pos++];
		for (int i = 1; i < cnt; i++) {
			Scope s = tmp[i];
			if (p.compareTo(s) != 0) {
				tmp[pos++] = s;
			}
		}
		return Arrays.copyOfRange(tmp, 0, pos);
	}
	
	private AST swapCmp(AST comparison) {
		switch (comparison.getType()) {
		case GeneralCompGE:
			comparison = new AST(GeneralCompLE);
			break;
		case GeneralCompGT:
			comparison = new AST(GeneralCompLT);
			break;
		case GeneralCompLE:
			comparison = new AST(GeneralCompGE);
			break;
		case GeneralCompLT:
			comparison = new AST(GeneralCompGT);
			break;
		case ValueCompGE:
			comparison = new AST(ValueCompLE);
			break;
		case ValueCompGT:
			comparison = new AST(ValueCompLT);
			break;
		case ValueCompLE:
			comparison = new AST(ValueCompGE);
			break;
		case ValueCompLT:
			comparison = new AST(ValueCompGT);
			break;
		}
		return comparison;
	}
}