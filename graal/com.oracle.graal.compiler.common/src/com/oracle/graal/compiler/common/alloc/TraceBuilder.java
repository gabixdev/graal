/*
 * Copyright (c) 2015, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.compiler.common.alloc;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.PriorityQueue;

import com.oracle.graal.compiler.common.cfg.AbstractBlockBase;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.debug.Debug.Scope;
import com.oracle.graal.debug.DebugCloseable;
import com.oracle.graal.debug.DebugMemUseTracker;
import com.oracle.graal.debug.DebugTimer;
import com.oracle.graal.debug.Indent;

public final class TraceBuilder<T extends AbstractBlockBase<T>> {

    public static final class TraceBuilderResult<T extends AbstractBlockBase<T>> {
        private final List<List<T>> traces;
        private final int[] blockToTrace;

        private TraceBuilderResult(List<List<T>> traces, int[] blockToTrace) {
            this.traces = traces;
            this.blockToTrace = blockToTrace;
        }

        public int getTraceForBlock(AbstractBlockBase<?> block) {
            return blockToTrace[block.getId()];
        }

        public List<List<T>> getTraces() {
            return traces;
        }

        public boolean incomingEdges(int traceNr) {
            List<T> trace = getTraces().get(traceNr);
            return incomingEdges(traceNr, trace);
        }

        public boolean incomingSideEdges(int traceNr) {
            List<T> trace = getTraces().get(traceNr);
            if (trace.size() <= 1) {
                return false;
            }
            return incomingEdges(traceNr, trace.subList(1, trace.size()));
        }

        private boolean incomingEdges(int traceNr, List<T> trace) {
            /* TODO (je): not efficient. find better solution. */
            for (T block : trace) {
                for (T pred : block.getPredecessors()) {
                    if (getTraceForBlock(pred) != traceNr) {
                        return true;
                    }
                }
            }
            return false;
        }

    }

    private static final String PHASE_NAME = "LIRPhaseTime_TraceBuilderPhase";
    private static final DebugTimer timer = Debug.timer(PHASE_NAME);
    private static final DebugMemUseTracker memUseTracker = Debug.memUseTracker(PHASE_NAME);

    /**
     * Build traces of sequentially executed blocks.
     */
    @SuppressWarnings("try")
    public static <T extends AbstractBlockBase<T>> TraceBuilderResult<T> computeTraces(T startBlock, List<T> blocks) {
        try (Scope s = Debug.scope("TraceBuilder")) {
            try (DebugCloseable a = timer.start(); DebugCloseable c = memUseTracker.start()) {
                return new TraceBuilder<>(blocks).build(startBlock);
            }
        }
    }

    private final PriorityQueue<T> worklist;
    private final BitSet processed;
    /**
     * Contains the number of unprocessed predecessors for every {@link AbstractBlockBase#getId()
     * block}.
     */
    private final int[] blocked;
    private final int[] blockToTrace;

    private TraceBuilder(List<T> blocks) {
        processed = new BitSet(blocks.size());
        worklist = createQueue();
        assert (worklist != null);

        blocked = new int[blocks.size()];
        blockToTrace = new int[blocks.size()];
        for (T block : blocks) {
            blocked[block.getId()] = block.getPredecessorCount();
        }
    }

    @SuppressWarnings("unchecked")
    private PriorityQueue<T> createQueue() {
        return (PriorityQueue<T>) new PriorityQueue<AbstractBlockBase<?>>(TraceBuilder::compare);
    }

    private static int compare(AbstractBlockBase<?> a, AbstractBlockBase<?> b) {
        return Double.compare(b.probability(), a.probability());
    }

    private boolean processed(T b) {
        return processed.get(b.getId());
    }

    private static final int RESULT_LOG_LEVEL = 1;

    @SuppressWarnings("try")
    private TraceBuilderResult<T> build(T startBlock) {
        try (Indent indent = Debug.logAndIndent("start trace building: " + startBlock)) {
            ArrayList<List<T>> traces = buildTraces(startBlock);

            assert verify(traces);
            if (Debug.isLogEnabled(RESULT_LOG_LEVEL)) {
                for (int i = 0; i < traces.size(); i++) {
                    List<T> trace = traces.get(i);
                    Debug.log(RESULT_LOG_LEVEL, "Trace %5d: %s", i, trace);
                }
            }
            return new TraceBuilderResult<>(traces, blockToTrace);
        }
    }

    private boolean verify(ArrayList<List<T>> traces) {
        assert verifyAllBlocksScheduled(traces, blocked.length) : "Not all blocks assigned to traces!";
        for (List<T> trace : traces) {
            T last = null;
            for (T current : trace) {
                assert last == null || current.getPredecessors().contains(last);
                last = current;
            }
        }
        return true;
    }

    private static <T extends AbstractBlockBase<T>> boolean verifyAllBlocksScheduled(ArrayList<List<T>> traces, int expectedLength) {
        BitSet handled = new BitSet(expectedLength);
        for (List<T> trace : traces) {
            for (T block : trace) {
                handled.set(block.getId());
            }
        }
        return handled.cardinality() == expectedLength;
    }

    protected ArrayList<List<T>> buildTraces(T startBlock) {
        ArrayList<List<T>> traces = new ArrayList<>();
        // add start block
        worklist.add(startBlock);
        // process worklist
        while (!worklist.isEmpty()) {
            T block = worklist.poll();
            assert block != null;
            if (!processed(block)) {
                traces.add(startTrace(block, traces.size()));
            }
        }
        return traces;
    }

    /**
     * Build a new trace starting at {@code block}.
     */
    @SuppressWarnings("try")
    private List<T> startTrace(T block, int traceNumber) {
        assert checkPredecessorsProcessed(block);
        ArrayList<T> trace = new ArrayList<>();
        int blockNumber = 0;
        try (Indent i = Debug.logAndIndent("StartTrace: " + block)) {
            for (T currentBlock = block; currentBlock != null; currentBlock = selectNext(currentBlock)) {
                Debug.log("add %s (prob: %f)", currentBlock, currentBlock.probability());
                processed.set(currentBlock.getId());
                trace.add(currentBlock);
                unblock(currentBlock);
                currentBlock.setLinearScanNumber(blockNumber++);
                blockToTrace[currentBlock.getId()] = traceNumber;
            }
        }
        return trace;
    }

    private boolean checkPredecessorsProcessed(T block) {
        List<T> predecessors = block.getPredecessors();
        for (T pred : predecessors) {
            if (!processed(pred)) {
                assert false : "Predecessor unscheduled: " + pred;
                return false;
            }

        }
        return true;
    }

    /**
     * Decrease the {@link #blocked} count for all predecessors and add them to the worklist once
     * the count reaches 0.
     */
    private void unblock(T currentBlock) {
        for (T successor : currentBlock.getSuccessors()) {
            if (!processed(successor)) {
                int blockCount = --blocked[successor.getId()];
                assert blockCount >= 0;
                if (blockCount == 0) {
                    worklist.add(successor);
                }
            }
        }
    }

    /**
     * @return The unprocessed predecessor with the highest probability, or {@code null}.
     */
    private T selectNext(T currentBlock) {
        T next = null;
        for (T succ : currentBlock.getSuccessors()) {
            if (!processed(succ) && (next == null || succ.probability() > next.probability())) {
                next = succ;
            }
        }
        return next;
    }
}
