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
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.*;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.binding.*;
import org.apache.jena.sparql.expr.*;
import org.apache.jena.sparql.syntax.*;
import org.junit.jupiter.api.Test;

class TestOrderDistinctCanonicalize {
    private static Op compile(String text) {
        return Algebra.compile(QueryFactory.create(text));
    }

    private static final List<SortCondition> ASC =
            List.of(new SortCondition(new ExprVar("s"), Query.ORDER_ASCENDING));

    private static Op normalize(Op op) {
        return Transformer.transformSkipService(new TransformOrderDistinctCanonicalize(), op);
    }

    private static List<Binding> rows(Op op) {
        DatasetGraph dataset = DatasetGraphFactory.create();
        var iterator = Algebra.exec(op, dataset);
        List<Binding> result = new ArrayList<>();
        try {
            while (iterator.hasNext()) result.add(BindingFactory.copy(iterator.nextBinding()));
        } finally {
            iterator.close();
            dataset.close();
        }
        return result;
    }

    private static void flatDistinct(Query query) {
        assertTrue(query.isDistinct(), query.toString());
        assertTrue(query.hasOrderBy(), query.toString());
        ElementWalker.walk(
                query.getQueryPattern(),
                new ElementVisitorBase() {
                    @Override
                    public void visit(ElementSubQuery subquery) {
                        fail("Unexpected subquery: " + query);
                    }
                });
        QueryFactory.create(query.toString());
    }

    @Test
    void fullBindingDistinctAndOrderSerializeAsOneQueryLevel() {
        Op pattern = compile("SELECT * {?s ?p ?o}");
        Op original = new OpOrder(OpDistinct.create(pattern), ASC);
        Op normalized = normalize(original);
        assertInstanceOf(OpDistinct.class, normalized);
        assertInstanceOf(OpOrder.class, ((OpDistinct) normalized).getSubOp());
        assertEquals(normalized, normalize(normalized));
        Query remote = AlgebraQueries.prepareForRemote(original).query();
        flatDistinct(remote);
        assertEquals(Set.of("s", "p", "o"), new HashSet<>(remote.getResultVars()));
        // Ordinary conversion and the caller's original algebra are untouched.
        assertFalse(AlgebraQueries.asQuery(original).isDistinct());
        assertInstanceOf(OpOrder.class, original);
    }

    @Test
    void widerProjectionRetainsSortColumnsAndDuplicateSemantics() {
        Op values = compile("SELECT * {VALUES (?s ?p ?o) {(3 10 1)(1 30 1)(1 30 2)(2 20 1)(2 20 1)}}");
        for (List<SortCondition> order :
                List.of(ASC, List.of(new SortCondition(new ExprVar("s"), Query.ORDER_DESCENDING)))) {
            Op original =
                    new OpOrder(
                            OpDistinct.create(new OpProject(values, List.of(Var.alloc("p"), Var.alloc("s")))),
                            order);
            var prepared = AlgebraQueries.prepareForRemote(original);
            flatDistinct(prepared.query());
            assertEquals(Set.of("p", "s"), new HashSet<>(prepared.query().getResultVars()));
            assertEquals(rows(original), rows(normalize(original)));
            assertEquals(rows(original), rows(Algebra.compile(prepared.query())));
            assertEquals(3, rows(normalize(original)).size());
        }
    }

    @Test
    void redundantDistinctDirectlyBelowProjectionDoesNotRequireASubquery() {
        Op values = compile("SELECT * {VALUES (?s ?p ?o) {(1 30 1)(1 30 2)(2 20 1)}}");
        Op original =
                new OpOrder(
                        OpDistinct.create(
                                new OpProject(OpDistinct.create(values), List.of(Var.alloc("p"), Var.alloc("s")))),
                        ASC);
        var prepared = AlgebraQueries.prepareForRemote(original);
        flatDistinct(prepared.query());
        assertEquals(rows(original), rows(Algebra.compile(prepared.query())));
        assertEquals(normalize(original), normalize(normalize(original)));
        Op protectedSlice =
                new OpOrder(
                        OpDistinct.create(
                                new OpProject(
                                        new OpSlice(OpDistinct.create(values), 0, 2),
                                        List.of(Var.alloc("s"), Var.alloc("p")))),
                        ASC);
        assertEquals(rows(protectedSlice), rows(normalize(protectedSlice)));
        Query slicedQuery = AlgebraQueries.prepareForRemote(protectedSlice).query();
        assertTrue(slicedQuery.toString().contains("LIMIT"));
    }

    @Test
    void globalSliceAndExistingSubquerySliceArePreserved() {
        Op values = compile("SELECT * {VALUES (?s ?p) {(3 10)(1 30)(1 30)(2 20)}}");
        Op original = new OpSlice(new OpOrder(OpDistinct.create(values), ASC), 1, 1);
        Query query = AlgebraQueries.prepareForRemote(original).query();
        flatDistinct(query);
        assertEquals(1, query.getOffset());
        assertEquals(1, query.getLimit());
        assertEquals(rows(original), rows(Algebra.compile(query)));
        Op limitedInput = new OpOrder(OpDistinct.create(new OpSlice(values, 0, 2)), ASC);
        assertEquals(rows(limitedInput), rows(normalize(limitedInput)));
        assertInstanceOf(
                OpSlice.class, ((OpOrder) ((OpDistinct) normalize(limitedInput)).getSubOp()).getSubOp());
        Op barrier = new OpOrder(new OpSlice(OpDistinct.create(values), 0, 2), ASC);
        assertEquals(barrier, normalize(barrier));
    }

    @Test
    void hiddenSortVariablesComputedKeysReducedAndTopNRemainUnchanged() {
        Op values = compile("SELECT * {VALUES (?s ?p) {(1 30)(2 20)}}");
        Op narrow = new OpOrder(OpDistinct.create(new OpProject(values, List.of(Var.alloc("p")))), ASC);
        assertEquals(narrow, normalize(narrow));
        assertEquals(rows(narrow), rows(normalize(narrow)));
        for (String text :
                List.of(
                        "SELECT * {VALUES ?s {1 2}} ORDER BY RAND()",
                        "SELECT * {VALUES ?s {1 2}} ORDER BY STR(?s)")) {
            var order = (OpOrder) compile(text);
            Op original = new OpOrder(OpDistinct.create(values), order.getConditions());
            assertEquals(original, normalize(original));
        }
        Op reduced = new OpOrder(OpReduced.create(values), ASC);
        assertEquals(reduced, normalize(reduced));
        Op top = new OpTopN(OpDistinct.create(values), 1, ASC);
        assertEquals(top, normalize(top));
    }

    @Test
    void termDistinctnessUnboundValuesAndBagsRemainIntact() {
        Op values = compile("SELECT * {VALUES (?s ?p) {(1 1)(1 1.0)(1 1)(UNDEF 2)(UNDEF 2)(2 3)}}");
        Op original = new OpOrder(OpDistinct.create(values), ASC);
        var expected = rows(original);
        var actual = rows(normalize(original));
        assertEquals(new HashSet<>(expected), new HashSet<>(actual));
        assertEquals(4, actual.size());
        assertEquals(
                expected.stream().map(b -> b.get(Var.alloc("s"))).toList(),
                actual.stream().map(b -> b.get(Var.alloc("s"))).toList());
        Op bag = new OpOrder(values, ASC);
        assertEquals(bag, normalize(bag));
        assertEquals(6, rows(normalize(bag)).size());
    }

    @Test
    void existingServicesStayOpaqueAndOutputRenamingIsPreserved() {
        Op values = compile("SELECT * {VALUES ?s {2 1 1}}");
        Op nested =
                new OpService(
                        NodeFactory.createURI("urn:nested"),
                        new OpOrder(OpDistinct.create(values), ASC),
                        false);
        assertSame(nested, normalize(nested));
        Var allocated = Var.alloc(".0");
        Op renamedInput = OpExtend.create(values, allocated, new ExprVar("s"));
        Op original =
                new OpOrder(
                        OpDistinct.create(new OpProject(renamedInput, List.of(allocated, Var.alloc("s")))),
                        ASC);
        var prepared = AlgebraQueries.prepareForRemote(original);
        flatDistinct(prepared.query());
        assertTrue(prepared.outputMapping().containsValue(allocated));
        assertEquals(
                Set.of(allocated, Var.alloc("s")), new HashSet<>(prepared.outputMapping().values()));
    }
}
