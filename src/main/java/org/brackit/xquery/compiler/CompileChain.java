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
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.brackit.xquery.compiler;

import java.util.Map;

import org.brackit.xquery.QueryException;
import org.brackit.xquery.XQuery;
import org.brackit.xquery.atomic.AnyURI;
import org.brackit.xquery.atomic.QNm;
import org.brackit.xquery.atomic.Str;
import org.brackit.xquery.compiler.analyzer.Analyzer;
import org.brackit.xquery.compiler.optimizer.Optimizer;
import org.brackit.xquery.compiler.optimizer.TopDownOptimizer;
import org.brackit.xquery.compiler.parser.XQParser;
import org.brackit.xquery.compiler.translator.BlockTranslator;
import org.brackit.xquery.compiler.translator.TopDownTranslator;
import org.brackit.xquery.compiler.translator.Translator;
import org.brackit.xquery.function.bit.Every;
import org.brackit.xquery.function.bit.GetForkBuffer;
import org.brackit.xquery.function.bit.GetPoolSize;
import org.brackit.xquery.function.bit.GetSerializerPermits;
import org.brackit.xquery.function.bit.Parse;
import org.brackit.xquery.function.bit.Put;
import org.brackit.xquery.function.bit.Serialize;
import org.brackit.xquery.function.bit.SetForkBuffer;
import org.brackit.xquery.function.bit.SetPoolSize;
import org.brackit.xquery.function.bit.SetSerializerPermits;
import org.brackit.xquery.function.bit.Silent;
import org.brackit.xquery.function.bit.Some;
import org.brackit.xquery.function.io.Readline;
import org.brackit.xquery.function.io.Writeline;
import org.brackit.xquery.module.Functions;
import org.brackit.xquery.module.Module;
import org.brackit.xquery.module.Namespaces;
import org.brackit.xquery.util.dot.DotUtil;
import org.brackit.xquery.xdm.Signature;
import org.brackit.xquery.xdm.type.AnyItemType;
import org.brackit.xquery.xdm.type.AtomicType;
import org.brackit.xquery.xdm.type.Cardinality;
import org.brackit.xquery.xdm.type.SequenceType;

/**
 * Compiles an {@link Module XQuery module}.
 * 
 * @author Sebastian Baechle
 * 
 */
public class CompileChain {

	public static final QNm PUSH_EVAL_OPTION = new QNm(Namespaces.BIT_NSURI,
			Namespaces.BIT_PREFIX, "push-evaluation");

	public static final Every BIT_EVERY_FUNC = new Every(new QNm(
			Namespaces.XML_NSURI, Namespaces.BIT_PREFIX, "every"),
			new Signature(new SequenceType(AtomicType.BOOL, Cardinality.One),
					new SequenceType(AnyItemType.ANY, Cardinality.ZeroOrMany)));

	public static final Some BIT_SOME_FUNC = new Some(new QNm(
			Namespaces.XML_NSURI, Namespaces.BIT_PREFIX, "some"),
			new Signature(new SequenceType(AtomicType.BOOL, Cardinality.One),
					new SequenceType(AnyItemType.ANY, Cardinality.ZeroOrMany)));

	static {
		Functions.predefine(BIT_SOME_FUNC);
		Functions.predefine(BIT_EVERY_FUNC);
		Functions.predefine(new Put(Put.PUT, new Signature(new SequenceType(
				AtomicType.STR, Cardinality.One), new SequenceType(
				AtomicType.STR, Cardinality.One))));
		Functions.predefine(new Put(Put.PUT, new Signature(new SequenceType(
				AtomicType.STR, Cardinality.One), new SequenceType(
				AtomicType.STR, Cardinality.One), new SequenceType(
				AtomicType.STR, Cardinality.ZeroOrOne))));
		Functions.predefine(new Readline());
		Functions.predefine(new Writeline());
		Functions.predefine(new Silent());
		Functions.predefine(new Parse());
		Functions.predefine(new SetPoolSize());
		Functions.predefine(new GetPoolSize());
		Functions.predefine(new Serialize());
		Functions.predefine(new SetForkBuffer());
		Functions.predefine(new GetForkBuffer());
		Functions.predefine(new GetSerializerPermits());
		Functions.predefine(new SetSerializerPermits());
	}

	final AnyURI baseURI;

	public CompileChain() {
		baseURI = null;
	}

	public CompileChain(AnyURI baseURI) {
		this.baseURI = baseURI;
	}

	protected Optimizer getOptimizer(Map<QNm, Str> options) {
		Str opt = options.get(PUSH_EVAL_OPTION);
		boolean push = ((opt != null) && Boolean
				.parseBoolean(opt.stringValue()));
		return new TopDownOptimizer(push);
	}

	protected Translator getTranslator(Map<QNm, Str> options) {
		Str opt = options.get(PUSH_EVAL_OPTION);
		boolean push = ((opt != null) && Boolean
				.parseBoolean(opt.stringValue()));
		return (push) ? new BlockTranslator() : new TopDownTranslator();
	}

	protected ModuleResolver getModuleResolver() {
		return new BaseResolver();
	}

	protected AST parse(String query) throws QueryException {
		return new XQParser(query).parse();
	}

	public Module compile(String query) throws QueryException {
		if (XQuery.DEBUG) {
			System.out.println(String.format("Compiling:\n%s", query));
		}
		ModuleResolver resolver = getModuleResolver();
		AST parsed = parse(query);
		if (XQuery.DEBUG) {
			DotUtil.drawDotToFile(parsed.dot(), XQuery.DEBUG_DIR, "parsed");
		}
		Analyzer analyzer = new Analyzer(resolver, baseURI, parsed);
		Module module = analyzer.getModules().get(0);
		// optimize all targets of all modules
		for (Target t : analyzer.getTargets()) {
			t.optimize(getOptimizer(module.getOptions()));
		}
		// translate all targets of all modules
		for (Target t : analyzer.getTargets()) {
			t.translate(getTranslator(module.getOptions()));
		}
		// everything went fine - add compiled modules to library
		for (Module m : analyzer.getModules()) {
			if (m.getTargetNS() != null) {
				resolver.register(m.getTargetNS(), m);
			}
		}
		AST xquery = analyzer.getAST();
		if (XQuery.DEBUG) {
			DotUtil.drawDotToFile(xquery.dot(), XQuery.DEBUG_DIR, "xquery");
		}
		return module;
	}
}
