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
package org.brackit.xquery.util.forkjoin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Thread pool for fork/join tasks.
 * 
 * As soon as we are sure that all our parallel processing fully fits to Java
 * 7's fork/join framework, we should abandon this package anyway and stick with
 * the optimized fork/join implementation of respective Java 7 JVMs.
 * 
 * @author Sebastian Baechle
 * 
 */
public class Pool {
	private static final boolean LOG = false;
	private final int size;
	private final Worker[] workers;
	private final ConcurrentLinkedQueue<Worker> inactive;

	public Pool(int size, WorkerFactory factory) {
		this.size = size;
		inactive = new ConcurrentLinkedQueue<Worker>();
		workers = new Worker[size];
		for (int i = 0; i < size; i++) {
			workers[i] = factory.newThread(this);
			workers[i].setDaemon(true);
		}
		for (int i = 0; i < size; i++) {
			workers[i].start();
		}
	}

	public int getSize() {
		return size;
	}

	public void signalWork() {
		Worker w = inactive.poll();
		if (w != null) {
			LockSupport.unpark(w);
		}
	}

	Task stealTask(Worker stealer) {
		Task t;
		if ((stealer.victim != null)
				&& ((t = stealer.victim.steal()) != null)) {
			if (LOG) {
				System.out.println(stealer + " stole from last victim "
						+ stealer.victim);
			}
			stealer.stats.stealCnt++;
			stealer.victim.stats.robbedCnt++;
			return t;
		}
		Worker[] ws = workers;
		for (int i = 0; i < ws.length; i++) {
			if ((t = ws[i].steal()) != null) {
				if (LOG) {
					System.out.println(stealer + " stole from " + ws[i]);
				}
				stealer.victim = ws[i];
				stealer.stats.stealCnt++;
				stealer.victim.stats.robbedCnt++;
				return t;
			}
		}
		stealer.victim = null;
		return null;
	}

	public Task submit(Task task) {
		Thread me;
		if ((me = Thread.currentThread()) instanceof Worker) {
			((Worker) me).fork(task);
			return task;
		}
		Worker w = inactive.poll();
		if (w != null) {
			w.push(task);
			LockSupport.unpark(w);
		} else {
			// TODO choose random active worker
			w = workers[0];
			w.push(task);
		}
		return task;
	}

	public boolean dispatch(Task task) {
		Worker w = inactive.poll();
		if (w != null) {
			w.push(task);
			LockSupport.unpark(w);
			return true;
		} else {
//			Thread me;
//			if ((me = Thread.currentThread()) instanceof Worker) {
//				for (int i = 0; i < size; i++) {
//					if (workers[i] != me) {
//						workers[i].push(task);
//						return true;
//					}
//				}
//				return false;
//			}
//			// TODO choose random active worker
//			workers[0].push(task);
//			return true;
			w = workers[0];
			w.push(task);
			return (w != Thread.currentThread());
		}
	}

	void join(Worker w, Task join) {
		Task t;
		int s;
		int retry = 0;
		while ((s = join.status) <= 0) {
			if ((t = w.poll()) != null) {
				exec(w, t);
				retry = 0;
			} else if ((t = stealTask(w)) != null) {
				// process stolen task from other thread
				t.exec();
				retry = 0;
			} else if (++retry == 16) {
				LockSupport.parkNanos(100);
				retry = 0;
			}
		}
	}

	void run(Worker w) {
		int retry = 0;
		while (!w.isTerminate()) {
			Task t;
			if ((t = w.poll()) != null) {
				exec(w, t);
				retry = 0;
			} else if ((t = stealTask(w)) != null) {
				t.exec();
				retry = 0;
			} else if (++retry == 64) {
				inactive.add(w);
				if ((t = w.poll()) == null) {
					if (LOG) {
						System.out.println(w + " goes parking");
					}
					LockSupport.park();
					if (LOG) {
						System.out.println(w + " unparking");
					}					
				} else {
					exec(w, t);
				}
				retry = 0;				
			} else if (retry % 16 == 0) {
				LockSupport.parkNanos(100);
			}
		}
	}

	private void exec(Worker w, Task t) {
		long start = System.currentTimeMillis();
		t.exec();
		long end = System.currentTimeMillis();
		w.stats.execCnt++;
		w.stats.execTime += (end - start);
	}

	public List<WorkerStats> getStats() {
		ArrayList<WorkerStats> stats = new ArrayList<WorkerStats>(
				workers.length);
		for (Worker w : workers) {
			stats.add(w.stats);
		}
		return stats;
	}
}