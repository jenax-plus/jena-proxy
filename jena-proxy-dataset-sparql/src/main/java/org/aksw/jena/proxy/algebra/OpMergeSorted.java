/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */

package org.aksw.jena.proxy.algebra;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.query.Query;
import org.apache.jena.query.SortCondition;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.Transform;
import org.apache.jena.sparql.algebra.op.OpExt;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingComparator;
import org.apache.jena.sparql.engine.iterator.*;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.expr.ExprEvalException;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.apache.jena.sparql.util.NodeIsomorphismMap;

/**
 * Sequential k-way merge of branches already sorted by the supplied keys. Keeps at most one
 * head binding per branch; equal keys preserve all rows and have unspecified relative order.
 * The caller must ensure branch ordering uses compatible RDF term comparison semantics.
 *
 * Install after ordinary optimization. effectiveOp is the original standard sort, also used
 * when evaluation receives correlated or multiple input bindings: SERVICE's per-binding
 * ordering would otherwise fail to establish a global order across those inputs.
 */
public final class OpMergeSorted extends OpExt {
    private final List<Op> branches;
    private final List<SortCondition> conditions;
    private final Op fallback;

    /**
     * Create a merge over branches that are already sorted by the shared conditions.
     *
     * @param branches the sorted branch operations, in merge order
     * @param conditions the shared sort conditions the branches must satisfy
     * @param fallback the ordinary sort used when the merge is not applicable
     */
    public OpMergeSorted(List<Op> branches, List<SortCondition> conditions, Op fallback) {
        super("merge-sorted");
        this.branches = List.copyOf(branches);
        this.conditions = List.copyOf(conditions);
        this.fallback = java.util.Objects.requireNonNull(fallback);
    }

    /**
     * The branch operations, in merge order.
     *
     * @return the sorted branch operations
     */
    public List<Op> getBranches() { return branches; }
    /**
     * The shared sort conditions the branches must already satisfy.
     *
     * @return the shared sort conditions
     */
    public List<SortCondition> getConditions() { return conditions; }
    @Override public Op effectiveOp() { return fallback; }
    // TransformCopy delegates OpExt back to apply; do not dispatch to it recursively.
    @Override public Op apply(Transform transform) { return this; }

    @Override
    public QueryIterator eval(QueryIterator input, ExecutionContext execCxt) {
        // Preserve the exact input iterator on fallback: ARQ distinguishes root input
        // from other iterators when evaluating subquery projections.
        if (!(input instanceof QueryIterRoot root) || !root.isJoinIdentity()) {
            return QC.execute(fallback, input, execCxt);
        }
        try {
            if (!input.hasNext()) return input;
            input.close();
            return new MergeIterator(execCxt);
        } catch (RuntimeException ex) {
            try { input.close(); } catch (RuntimeException close) { ex.addSuppressed(close); }
            throw ex;
        }
    }

    @Override public int hashCode() { return java.util.Objects.hash(branches, conditions, fallback); }
    @Override
    public boolean equalTo(Op other, NodeIsomorphismMap labels) {
        if (!(other instanceof OpMergeSorted merge) || !conditions.equals(merge.conditions)
                || branches.size() != merge.branches.size() || !fallback.equalTo(merge.fallback, labels)) return false;
        for (int i = 0; i < branches.size(); i++) {
            if (!branches.get(i).equalTo(merge.branches.get(i), labels)) return false;
        }
        return true;
    }

    @Override
    public void outputArgs(IndentedWriter out, SerializationContext context) {
        out.print(conditions.toString());
        for (Op branch : branches) {
            out.println();
            branch.output(out, context);
        }
    }

    private record Head(int branch, Binding binding, List<NodeValue> keys) {}

    private final class MergeIterator extends QueryIter {
        // Cancellation may arrive on another thread; execution and contexts stay sequential.
        private final List<QueryIterator> children = new CopyOnWriteArrayList<>();
        private final PriorityQueue<Head> heads;
        private boolean initialized;
        private int pending = -1;
        private volatile boolean cancelled;

        MergeIterator(ExecutionContext context) {
            super(context);
            heads = new PriorityQueue<>((a, b) -> {
                for (int i = 0; i < conditions.size(); i++) {
                    int cmp = BindingComparator.compareNodesRaw(a.keys().get(i), b.keys().get(i));
                    if (conditions.get(i).getDirection() == Query.ORDER_DESCENDING) cmp = -cmp;
                    if (cmp != 0) return cmp;
                }
                // Do not use BindingComparator's syntactic binding tie-break: endpoints may
                // resolve ties differently, especially after scoped variable renaming.
                return Integer.compare(a.branch(), b.branch());
            });
        }

        private void readHead(int index) {
            QueryIterator child = children.get(index);
            if (child.hasNext()) {
                Binding binding = child.nextBinding();
                List<NodeValue> keys = new ArrayList<>(conditions.size());
                for (SortCondition condition : conditions) {
                    NodeValue key = null;
                    try { key = condition.getExpression().eval(binding, getExecContext()); }
                    catch (ExprEvalException ex) { /* Errors/unbound sort before bound values, as in ARQ. */ }
                    keys.add(key);
                }
                heads.add(new Head(index, binding, keys));
            } else {
                child.close();
            }
        }

        @Override
        protected boolean hasNextBinding() {
            try {
                if (!initialized) {
                    initialized = true;
                    for (Op branch : branches) {
                        QueryIterator branchInput = QueryIterRoot.create(getExecContext());
                        QueryIterator child;
                        try {
                            child = QC.execute(branch, branchInput, getExecContext());
                        } catch (RuntimeException ex) {
                            try { branchInput.close(); } catch (RuntimeException close) { ex.addSuppressed(close); }
                            throw ex;
                        }
                        children.add(child);
                        if (cancelled) child.cancel();
                        readHead(children.size() - 1);
                    }
                }
                if (pending >= 0) {
                    int index = pending;
                    pending = -1;
                    readHead(index);
                }
                return !heads.isEmpty();
            } catch (RuntimeException ex) {
                try { close(); } catch (RuntimeException close) { ex.addSuppressed(close); }
                throw ex;
            }
        }

        @Override
        protected Binding moveToNextBinding() {
            Head next = heads.remove();
            // Delay the next read until demanded, so LIMIT/close need not fetch another row.
            pending = next.branch();
            return next.binding();
        }

        @Override
        protected void closeIterator() {
            RuntimeException failure = null;
            for (QueryIterator child : children) {
                try { child.close(); }
                catch (RuntimeException ex) {
                    if (failure == null) failure = ex; else failure.addSuppressed(ex);
                }
            }
            heads.clear();
            if (failure != null) throw failure;
        }

        @Override
        protected void requestCancel() {
            cancelled = true;
            RuntimeException failure = null;
            for (QueryIterator child : children) {
                try { child.cancel(); }
                catch (RuntimeException ex) {
                    if (failure == null) failure = ex; else failure.addSuppressed(ex);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
