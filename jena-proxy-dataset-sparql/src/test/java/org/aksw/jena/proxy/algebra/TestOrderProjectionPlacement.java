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
import org.apache.jena.query.*;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.binding.*;
import org.apache.jena.sparql.expr.*;
import org.junit.jupiter.api.Test;

class TestOrderProjectionPlacement {
    private static Op compile(String text) {
        return Algebra.compile(QueryFactory.create(text, Syntax.syntaxARQ));
    }

    private static Op rewrite(Op op) {
        op = Transformer.transformSkipService(new TransformNormalizeJoinIdentities(), op);
        op = Transformer.transformSkipService(new TransformDistinctOrderProjection(), op);
        op = Transformer.transformSkipService(new TransformProjectionPlacement(), op);
        op = TransformDistinctPlacement.rewrite(op, true);
        op = Algebra.optimize(op);
        op = Transformer.transformSkipService(new TransformNormalizeJoinIdentities(), op);
        op = Transformer.transformSkipService(new TransformDistinctOrderProjection(), op);
        op = Transformer.transformSkipService(new TransformProjectionPlacement(), op);
        op = TransformDistinctPlacement.rewrite(op, true);
        return OrderPlanning.rewrite(op, service -> false);
    }

    private static List<Binding> rows(Op op) {
        DatasetGraph dataset = DatasetGraphFactory.create();
        var it = Algebra.exec(op, dataset);
        List<Binding> result = new ArrayList<>();
        try {
            while (it.hasNext()) result.add(BindingFactory.copy(it.nextBinding()));
        } finally {
            it.close();
            dataset.close();
        }
        return result;
    }

    private static Map<Binding, Integer> bag(List<Binding> bindings) {
        Map<Binding, Integer> result = new HashMap<>();
        bindings.forEach(b -> result.merge(b, 1, Integer::sum));
        return result;
    }

    @Test
    void normalizeUnitsButKeepValuesMultiplicitiesAndProjectionBoundaries() {
        Op values = compile("SELECT * { VALUES ?x {1 1 2} }");
        Op protectedScope =
                new OpProject(
                        OpDistinct.create(new OpProject(values, List.of(Var.alloc("x")))),
                        List.of(Var.alloc("x")));
        OpSequence sequence = OpSequence.create();
        sequence.add(OpTable.unit());
        sequence.add(protectedScope);
        Op normalized = Transformer.transform(new TransformNormalizeJoinIdentities(), sequence);
        assertSame(protectedScope, normalized);
        Op joined = OpJoin.create(values, OpJoin.create(OpTable.unit(), protectedScope));
        assertEquals(
                rows(joined), rows(Transformer.transform(new TransformNormalizeJoinIdentities(), joined)));
        assertEquals(3, rows(joined).size());
    }

    @Test
    void widerProjectionRetainsHiddenSortColumnsUntilFinalProjection() {
        Op original =
                compile(
                        "SELECT DISTINCT ?p { {VALUES (?s ?p ?o) {(1 30 7)(3 30 9)}} UNION {VALUES (?s ?p ?o) {(2 20 8)(4 10 4)}} } ORDER BY ?s");
        assertEquals(rows(original), rows(rewrite(original)));
        assertEquals(
                List.of("30", "20", "10"),
                rows(rewrite(original)).stream()
                        .map(b -> b.get(Var.alloc("p")).getLiteralLexicalForm())
                        .toList());
        assertTrue(rows(rewrite(original)).stream().allMatch(b -> b.size() == 1));
    }

    @Test
    void projectedBagsAndGlobalDistinctLimitOffsetKeepCardinalities() {
        String body =
                "{VALUES (?s ?p ?o) {(1 3 7)(1 3 8)(3 1 5)}} UNION {VALUES (?s ?p ?o) {(1 3 7)(2 2 6)}}";
        for (String suffix :
                List.of(
                        "SELECT ?p WHERE { " + body + " } ORDER BY ?s",
                        "SELECT DISTINCT ?p WHERE { " + body + " } ORDER BY ?p LIMIT 2 OFFSET 1",
                        "SELECT DISTINCT ?p WHERE { " + body + " } ORDER BY DESC(?s) LIMIT 2")) {
            Op original = compile(suffix);
            assertEquals(rows(original), rows(rewrite(original)), suffix);
        }
    }

    @Test
    void originalSubqueryProjectionDoesNotExposeHiddenVariables() {
        Op original =
                compile("SELECT DISTINCT ?p { { SELECT ?p { VALUES (?s ?p) {(1 3)(2 2)} } } } ORDER BY ?s");
        Op rewritten = rewrite(original);
        assertEquals(bag(rows(original)), bag(rows(rewritten)));
        assertTrue(rows(rewritten).stream().allMatch(b -> !b.contains(Var.alloc("s"))));
    }

    @Test
    void volatileOrderAndFilterRemainBarriersAndLocalTopNRemainsBounded() {
        Op local = compile("SELECT DISTINCT ?p {VALUES ?p {3 1 2}} ORDER BY ?p LIMIT 1");
        Op optimized = Algebra.optimize(local);
        assertEquals(optimized, Transformer.transform(new TransformOrderPlacement(), optimized));
        Op volatileOrder = compile("SELECT DISTINCT ?p {VALUES ?p {1 2}} ORDER BY RAND()");
        assertEquals(
                volatileOrder,
                Transformer.transform(new TransformDistinctOrderProjection(), volatileOrder));
        Op filter = compile("SELECT ?p {VALUES ?p {1 2} FILTER(RAND()>0)} ORDER BY ?p");
        assertEquals(filter, Transformer.transform(new TransformOrderPlacement(), filter));
    }

    @Test
    void serviceProjectionUsesOnlyPlainPatternsAndNoPseudoLocalMerge() {
        Op query = compile("SELECT ?p { SERVICE <urn:approved> { ?s ?p ?o } }");
        Op result = Transformer.transformSkipService(new TransformProjectService(s -> true), query);
        OpService remote = ((OpProject) result).getSubOp() instanceof OpService s ? s : null;
        assertNotNull(remote);
        assertEquals(List.of(Var.alloc("p")), ((OpProject) remote.getSubOp()).getVars());
        Op local = compile("SELECT ?p { VALUES (?s ?p) {(1 2)(2 3)} } ORDER BY ?p");
        assertEquals(
                local, Transformer.transformSkipService(new TransformOrderService(s -> false), local));
    }

    @Test
    void rdfTermsWithEqualSortValuesStayDistinct() {
        Op query =
                compile(
                        "SELECT DISTINCT ?x { {VALUES ?x {1 1.0 1}} UNION {VALUES ?x {1.0 2}} } ORDER BY ?x");
        assertEquals(bag(rows(query)), bag(rows(rewrite(query))));
        assertEquals(3, rows(rewrite(query)).size());
    }
}
