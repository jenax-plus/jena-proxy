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

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.SortCondition;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.*;
import org.apache.jena.sparql.engine.binding.*;
import org.apache.jena.sparql.engine.iterator.*;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.expr.*;
import org.junit.jupiter.api.Test;

class TestOrderTransforms {
    private static final List<SortCondition> ASC = List.of(new SortCondition(new ExprVar("x"), Query.ORDER_ASCENDING));
    private static Op compile(String query) { return Algebra.compile(QueryFactory.create(query)); }
    private static Op rewrite(Op op) {
        return Transformer.transformSkipService(new TransformOrderUnion(), op);
    }
    private static List<Binding> rows(Op op) {
        DatasetGraph dataset = DatasetGraphFactory.create();
        QueryIterator it = Algebra.exec(op, dataset);
        try { return collect(it); } finally { it.close(); dataset.close(); }
    }
    private static List<Binding> collect(QueryIterator it) {
        List<Binding> result = new ArrayList<>();
        while (it.hasNext()) result.add(BindingFactory.copy(it.nextBinding()));
        return result;
    }
    private static Op values(String values) { return compile("SELECT * { VALUES ?x { " + values + " } }"); }
    private static Map<Binding, Integer> bag(List<Binding> rows) {
        Map<Binding, Integer> result = new HashMap<>();
        rows.forEach(row -> result.merge(row, 1, Integer::sum));
        return result;
    }

    @Test void distributionPreservesDuplicatesUnboundAndEmptyBranches() {
        for (String order : List.of("?x", "DESC(?x)", "?x DESC(?y)")) {
            Op original = compile("SELECT * { { VALUES (?x ?y) { (3 0) (1 2) (1 2) (UNDEF 5) } } "
                    + "UNION { VALUES (?x ?y) { (2 1) (1 3) (UNDEF 4) } } UNION { VALUES ?x {} } } ORDER BY " + order);
            Op result = rewrite(original);
            assertInstanceOf(OpMergeSorted.class, result);
            assertEquals(bag(rows(original)), bag(rows(result)));
            var conditions = ((OpMergeSorted)result).getConditions();
            assertEquals(rows(original).stream().map(b -> conditions.stream()
                    .map(c -> b.get(c.getExpression().asVar())).toList()).toList(),
                    rows(result).stream().map(b -> conditions.stream()
                    .map(c -> b.get(c.getExpression().asVar())).toList()).toList());
            assertEquals(result, rewrite(result));
        }
        Op mixedTerms = new OpOrder(OpUnion.create(values("<urn:z> 1.5 true UNDEF"),
                values("<urn:a> 1 \"a\" \"a\"@en")), ASC);
        assertEquals(rows(mixedTerms), rows(rewrite(mixedTerms)));
        Op empty = new OpOrder(OpDisjunction.create(), ASC);
        assertEquals(List.of(), rows(rewrite(empty)));
    }

    @Test void tiesHaveUnspecifiedOrderButKeepAllRows() {
        Op original = compile("SELECT * { { VALUES (?x ?tag) { (1 9) (1 7) } } UNION "
                + "{ VALUES (?x ?tag) { (1 8) (1 6) } } } ORDER BY ?x");
        assertEquals(bag(rows(original)), bag(rows(rewrite(original))));
    }

    @Test void naryAndGeneratedNumericPartitionsAreSorted() {
        Random random = new Random(51);
        for (int run = 0; run < 150; run++) {
            OpDisjunction union = OpDisjunction.create();
            for (int branch = 0; branch < random.nextInt(5) + 1; branch++) {
                StringBuilder entries = new StringBuilder();
                for (int n = random.nextInt(15); n > 0; n--) entries.append(random.nextInt(7) - 3).append(' ');
                union.add(values(entries.toString()));
            }
            List<SortCondition> keys = List.of(new SortCondition(new ExprVar("x"),
                    run % 2 == 0 ? Query.ORDER_ASCENDING : Query.ORDER_DESCENDING));
            Op original = new OpOrder(union, keys);
            assertEquals(rows(original), rows(rewrite(original)), "run " + run);
        }
    }

    @Test void limitsOffsetsAndTopNStayGlobal() {
        Op union = OpUnion.create(values("9 1 5 1"), values("8 2 6 2"));
        Op original = new OpSlice(new OpOrder(union, ASC), 2, 3);
        assertEquals(rows(original), rows(rewrite(original)));
        Op top = new OpTopN(union, 5, ASC);
        assertInstanceOf(OpSlice.class, rewrite(top));
        assertEquals(rows(top), rows(rewrite(top)));
    }

    @Test void opaqueAndComputedSortExpressionsStayLocal() {
        for (String condition : List.of("RAND()", "NOW()", "(?x + 1)", "<urn:custom>(?x)")) {
            Op original = compile("SELECT * { { VALUES ?x { 1 } } UNION { VALUES ?x { 2 } } } ORDER BY " + condition);
            assertEquals(original, rewrite(original));
        }
    }

    @Test void serviceEligibilityAndConstantAliasNormalization() {
        TransformOrderService transform = new TransformOrderService(s -> s.getService().equals(NodeFactory.createURI("urn:approved")));
        for (String service : List.of("<urn:approved>", "SILENT <urn:approved>", "?endpoint", "<urn:other>")) {
            Op op = compile("SELECT * { SERVICE " + service + " { ?s ?p ?x } } ORDER BY ?x");
            Op result = Transformer.transformSkipService(transform, op);
            if (service.equals("<urn:approved>")) {
                OpMergeSorted merge = assertInstanceOf(OpMergeSorted.class, result);
                OpService remote = assertInstanceOf(OpService.class, merge.getBranches().get(0));
                assertInstanceOf(OpOrder.class, remote.getSubOp());
            } else assertEquals(op, result);
        }
        Op original = compile("SELECT * { VALUES ?g { <urn:g> } SERVICE <urn:approved> { ?s ?p ?x } } ORDER BY ?g DESC(?x)");
        // Both the pre-optimizer Join and post-optimizer Sequence shape must work.
        for (Op op : List.of(original, Algebra.optimize(original))) {
            OpMergeSorted merge = assertInstanceOf(OpMergeSorted.class, Transformer.transformSkipService(transform, op));
            OpExtend extend = assertInstanceOf(OpExtend.class, merge.getBranches().get(0));
            OpService service = assertInstanceOf(OpService.class, extend.getSubOp());
            OpOrder order = assertInstanceOf(OpOrder.class, service.getSubOp());
            assertEquals(1, order.getConditions().size());
            assertEquals(new ExprVar("x"), order.getConditions().get(0).getExpression());
        }
    }

    @Test void correlatedAndMultipleInputBindingsUseOriginalSort() {
        Op original = new OpOrder(OpUnion.create(values("4 2"), values("3 1")), ASC);
        Op result = rewrite(original);
        DatasetGraph dataset = DatasetGraphFactory.create();
        ExecutionContext context = ExecutionContext.create(dataset);
        for (List<Binding> input : List.of(List.of(BindingFactory.binding(Var.alloc("other"), NodeFactory.createURI("urn:a"))),
                List.of(BindingFactory.empty(), BindingFactory.empty()))) {
            QueryIterator expected = QC.execute(original, QueryIterPlainWrapper.create(input.iterator(), context), context);
            QueryIterator actual = QC.execute(result, QueryIterPlainWrapper.create(input.iterator(), context), context);
            try { assertEquals(collect(expected), collect(actual)); }
            finally { expected.close(); actual.close(); }
        }
        dataset.close();
    }

    @Test void stableFiltersAndCompatibleProjectionsExposeUnions() {
        Op union = OpUnion.create(values("4 2 2"), values("3 1 1"));
        Op filtered = OpFilter.filter(new E_GreaterThan(new ExprVar("x"), NodeValue.makeInteger(1)), union);
        for (Op input : List.of(filtered, new OpProject(filtered, List.of(Var.alloc("x"))))) {
            Op original = new OpOrder(input, ASC);
            Op result = rewrite(original);
            assertNotEquals(original, result);
            assertEquals(rows(original), rows(result));
        }
        Op hidden = new OpOrder(new OpProject(union, List.of(Var.alloc("other"))), ASC);
        assertEquals(hidden, rewrite(hidden));
        Op volatileFilter = new OpOrder(OpFilter.filter(new E_GreaterThan(new E_Random(),
                NodeValue.makeInteger(0)), union), ASC);
        assertEquals(volatileFilter, rewrite(volatileFilter));
    }

    @Test void fallbackPreservesRootProjectionSemanticsAndEmptyInputs() {
        Op union = OpUnion.create(new OpProject(values("2"), List.of(Var.alloc("x"))), values("1"));
        Op original = new OpOrder(union, ASC);
        DatasetGraph dataset = DatasetGraphFactory.create();
        ExecutionContext context = ExecutionContext.create(dataset);
        Binding initial = BindingFactory.binding(Var.alloc("other"), NodeFactory.createURI("urn:a"));
        QueryIterator expected = QC.execute(original, QueryIterRoot.create(initial, context), context);
        QueryIterator actual = QC.execute(rewrite(original), QueryIterRoot.create(initial, context), context);
        try { assertEquals(collect(expected), collect(actual)); }
        finally { expected.close(); actual.close(); }
        QueryIterator empty = QC.execute(rewrite(original),
                QueryIterPlainWrapper.create(Collections.<Binding>emptyIterator(), context), context);
        try { assertFalse(empty.hasNext()); } finally { empty.close(); dataset.close(); }
    }

    @Test void orderedBareGroupsKeepExplicitRemoteAggregateOutputs() {
        OpGroup group = assertInstanceOf(OpGroup.class,
                ((OpExtend)((OpProject)compile("SELECT (COUNT(*) AS ?c) { VALUES ?x { 1 2 } }")).getSubOp()).getSubOp());
        Var count = group.getAggregators().get(0).getVar();
        Op order = new OpOrder(group, List.of(new SortCondition(new ExprVar(count), Query.ORDER_DESCENDING)));
        var prepared = AlgebraQueries.prepareForRemote(order);
        Query reparsed = QueryFactory.create(prepared.query().toString());
        assertTrue(reparsed.hasOrderBy());
        Op reparsedOp = Algebra.compile(reparsed);
        List<Binding> result = rows(reparsedOp);
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).size());
        assertEquals(NodeValue.makeInteger(2).asNode(), result.get(0).get(reparsed.getProjectVars().get(0)));
        assertEquals(count, prepared.outputMapping().get(reparsed.getProjectVars().get(0)));
    }

    private static class Source extends OpExt {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger cancels = new AtomicInteger();
        final boolean fail;
        Source(boolean fail) { super("test-sorted-source"); this.fail = fail; }
        @Override public Op effectiveOp() { return values("1 2 3"); }
        @Override public QueryIterator eval(QueryIterator input, ExecutionContext c) {
            input.close();
            return new QueryIter(c) {
                int n;
                @Override protected boolean hasNextBinding() {
                    if (fail) throw new IllegalStateException("test failure");
                    return n < 3;
                }
                @Override protected Binding moveToNextBinding() {
                    reads.incrementAndGet();
                    return BindingFactory.binding(Var.alloc("x"), NodeValue.makeInteger(++n).asNode());
                }
                @Override protected void closeIterator() { closes.incrementAndGet(); }
                @Override protected void requestCancel() { cancels.incrementAndGet(); }
            };
        }
        @Override public int hashCode() { return System.identityHashCode(this); }
        @Override public boolean equalTo(Op op, org.apache.jena.sparql.util.NodeIsomorphismMap m) { return op == this; }
        @Override public void outputArgs(org.apache.jena.atlas.io.IndentedWriter w, org.apache.jena.sparql.serializer.SerializationContext c) {}
    }

    @Test void mergeReadsOneHeadAndClosesAllBranchesOnEarlyExit() {
        Source a = new Source(false), b = new Source(false);
        OpMergeSorted merge = new OpMergeSorted(List.of(a, b), ASC, values(""));
        DatasetGraph dataset = DatasetGraphFactory.create();
        QueryIterator it = Algebra.exec(merge, dataset);
        try {
            assertTrue(it.hasNext());
            it.nextBinding();
            assertEquals(1, a.reads.get());
            assertEquals(1, b.reads.get());
        } finally { it.close(); dataset.close(); }
        assertEquals(1, a.closes.get());
        assertEquals(1, b.closes.get());
    }

    @Test void mergeClosesAllOpenedBranchesOnFailureAndPropagatesCancellation() {
        for (boolean failure : List.of(true, false)) {
            Source a = new Source(false), b = new Source(failure);
            DatasetGraph dataset = DatasetGraphFactory.create();
            QueryIterator it = Algebra.exec(new OpMergeSorted(List.of(a, b), ASC, values("")), dataset);
            try {
                if (failure) assertThrows(IllegalStateException.class, it::hasNext);
                else {
                    assertTrue(it.hasNext());
                    it.cancel();
                    assertEquals(1, a.cancels.get());
                    assertEquals(1, b.cancels.get());
                }
            } finally { it.close(); dataset.close(); }
            assertEquals(1, a.closes.get());
            assertEquals(1, b.closes.get());
        }
    }
}
