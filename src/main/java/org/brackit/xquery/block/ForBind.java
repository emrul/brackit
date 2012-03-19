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
package org.brackit.xquery.block;

import org.brackit.xquery.QueryContext;
import org.brackit.xquery.QueryException;
import org.brackit.xquery.Tuple;
import org.brackit.xquery.atomic.Int32;
import org.brackit.xquery.atomic.IntNumeric;
import org.brackit.xquery.util.forkjoin.Task;
import org.brackit.xquery.xdm.Expr;
import org.brackit.xquery.xdm.Item;
import org.brackit.xquery.xdm.Iter;
import org.brackit.xquery.xdm.Sequence;

/**
 * 
 * @author Sebastian Baechle
 * 
 */
public class ForBind implements Block {

	final Expr expr;
	final boolean allowingEmpty;
	boolean bindVar = true;
	boolean bindPos = false;

	public ForBind(Expr bind, boolean allowingEmpty) {
		this.expr = bind;
		this.allowingEmpty = allowingEmpty;
	}

	@Override
	public int outputWidth(int initSize) {
		return initSize + ((bindVar) ? 1 : 0) + ((bindPos) ? 1 : 0);
	}

	@Override
	public Sink create(QueryContext ctx, Sink sink) throws QueryException {
		return new ForBindSink(ctx, sink);
	}
	
	public void bindVariable(boolean bindVariable) {
		this.bindVar = bindVariable;
	}

	public void bindPosition(boolean bindPos) {
		this.bindPos = bindPos;
	}

	private static class Buf {
		Tuple[] b;
		int len;

		Buf(int size) {
			this.b = new Tuple[size];
		}

		boolean add(Tuple t) {
			b[len++] = t;
			return len < b.length;
		}
	}

	private class Slice extends Task {
		final QueryContext ctx;
		final Sink s;
		final Tuple t;
		final Iter it;
		IntNumeric pos;
		volatile Slice fork;

		private Slice(QueryContext ctx, Sink s, Tuple t, Iter it, IntNumeric pos) {
			this.ctx = ctx;
			this.s = s;
			this.t = t;
			this.it = it;
			this.pos = pos;
		}

		public void compute() throws QueryException {
			fork = bind(it);
			while ((fork != null) && (fork.finished())) {
				fork = fork.fork;
			}
		}

		private Slice bind(Iter c) throws QueryException {
			Buf buf = new Buf(bufSize());
			Slice fork = fillBuffer(c, buf);
			if (buf.len == 0) {
				s.begin();
				s.end();
				return null;
			}
			// process local share
			output(buf);
			return fork;
		}

		private void output(Buf buf) throws QueryException {
			s.begin();
			try {
				for (int i = 0; i < buf.len; i++) {
					buf.b[i] = emit(t, (Sequence) buf.b[i]);
				}
				s.output(buf.b, buf.len);
			} catch (QueryException e) {
				s.fail();
				throw e;
			}
			s.end();
		}

		private Tuple emit(Tuple t, Sequence item) throws QueryException {
			if (bindVar) {
				if (bindPos) {
					return t.concat(new Sequence[] { item,
							(item != null) ? (pos = pos.inc()) : pos });
				} else {
					return t.concat(item);
				}
			} else if (bindPos) {
				return t.concat((item != null) ? (pos = pos.inc()) : pos);
			} else {
				return t;
			}
		}

		private Slice fillBuffer(Iter it, Buf buf) throws QueryException {
			while (true) {
				Item i = it.next();
				if (i != null) {
					if (!buf.add(i)) {
						IntNumeric npos = (IntNumeric) ((pos != null) ? pos
								.add(new Int32(buf.len)) : null);
						Slice fork = new Slice(ctx, s.fork(), t, it, npos);
						fork.fork();
						it = null;
						return fork;
					}
				} else {
					it.close();
					it = null; // allow garbage collection
					return null;
				}
			}
		}

		private int bufSize() {
			// TODO
			return 20;
		}
	}

	private class ForBindTask extends Task {
		private final QueryContext ctx;
		private final Tuple[] buf;
		private final int start;
		private final int end;
		private Sink sink;

		public ForBindTask(QueryContext ctx, Sink sink, Tuple[] buf, int start,
				int end) {
			this.ctx = ctx;
			this.sink = sink;
			this.buf = buf;
			this.start = start;
			this.end = end;
		}

		@Override
		public void compute() throws QueryException {
			if (end - start > 10) {
				int mid = start + ((end - start) / 2);
				ForBindTask a = new ForBindTask(ctx, sink.fork(), buf, mid, end);
				ForBindTask b = new ForBindTask(ctx, sink, buf, start, mid);
				a.fork();
				b.compute();
				a.join();
			} else {
				IntNumeric pos = (bindPos) ? Int32.ZERO : null;
				for (int i = start; i < end; i++) {
					Sequence s = expr.evaluate(ctx, buf[i]);
					Sink ss = sink;
					sink = sink.fork();
					if (s != null) {
						Slice sl = new Slice(ctx, ss, buf[i], s.iterate(), pos);
						sl.compute();
						Slice fork = sl.fork;
						while (fork != null) {
							fork.join();
							fork = fork.fork;
						}
					}
				}
				sink.begin();
				sink.end();
			}
		}
	}

	private class ForBindSink extends FJControl implements Sink {
		Sink s;
		final QueryContext ctx;

		private ForBindSink(QueryContext ctx, Sink s) {
			this.ctx = ctx;
			this.s = s;
		}

		@Override
		public void output(Tuple[] t, int len) throws QueryException {
			// fork sink for future output calls
			Sink ss = s;
			s = s.fork();
			ForBindTask task = new ForBindTask(ctx, ss, t, 0, len);
			task.compute();
		}

		@Override
		public Sink fork() {
			return new ForBindSink(ctx, s.fork());
		}

		@Override
		public Sink partition(Sink stopAt) {
			return new ForBindSink(ctx, s.partition(stopAt));
		}

		@Override
		public void fail() throws QueryException {
			s.begin();
			s.fail();
		}

		@Override
		public void begin() throws QueryException {
			// do nothing
		}

		@Override
		public void end() throws QueryException {
			s.begin();
			s.end();
		}
	}
}