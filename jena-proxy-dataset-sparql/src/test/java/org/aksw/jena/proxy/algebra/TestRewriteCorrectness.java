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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.binding.*;
import org.apache.jena.sparql.expr.*;
import org.apache.jena.sparql.function.*;
import org.junit.jupiter.api.Test;

class TestRewriteCorrectness {
    private static Op compile(String query) {
        return Algebra.compile(QueryFactory.create(query, Syntax.syntaxARQ));
    }

    private static Map<Binding, Integer> evaluate(Op op) {
        DatasetGraph dataset = DatasetGraphFactory.create();
        AtomicInteger next = new AtomicInteger();
        FunctionRegistry functions = new FunctionRegistry();
        functions.put("urn:audit:next", iri -> new FunctionBase0() {
            @Override public NodeValue exec() { return NodeValue.makeInteger(next.incrementAndGet()); }
        });
        FunctionRegistry.set(dataset.getContext(), functions);
        Map<Binding, Integer> result = new HashMap<>();
        // E_Function caches its registry binding. Fresh expression instances keep
        // original/transformed executions independent, including stateful functions.
        Op executable = Transformer.transform(new TransformCopy(), new ExprTransformCopy(true), op);
        var iterator = Algebra.exec(executable, dataset);
        try {
            while (iterator.hasNext()) result.merge(BindingFactory.copy(iterator.nextBinding()), 1, Integer::sum);
        } finally {
            iterator.close();
            dataset.close();
        }
        return result;
    }

    private static void samePull(String query) {
        Op original = compile(query);
        Op rewritten = TransformPullExtend.transform(original);
        assertEquals(evaluate(original), evaluate(rewritten), "Original: " + original + " Rewritten: " + rewritten);
    }

    private static void samePlacement(String query) {
        Op original = compile(query);
        Op rewritten = TransformDistinctPlacement.rewrite(original, true);
        assertEquals(evaluate(original), evaluate(rewritten), "Original: " + original + " Rewritten: " + rewritten);
    }

    @Test void distinctPlacementPreservesFilterOnlyExistsCorrelations() {
        String query = "SELECT DISTINCT ?y WHERE { VALUES (?x ?y) { (1 7) (2 7) } FILTER EXISTS { FILTER(?x = 1) } }";
        assertEquals(1, evaluate(compile(query)).size());
        samePlacement(query);
    }

    @Test void distinctPlacementPreservesExistsInComputedKeys() {
        String query = "SELECT DISTINCT (EXISTS { FILTER(?x = 1) } AS ?flag) WHERE { VALUES ?x { 1 2 } }";
        assertEquals(2, evaluate(compile(query)).size());
        samePlacement(query);
    }

    @Test void pullExtendPreservesFilterOnlyExistsCorrelations() {
        String query = "SELECT ?x ?flag WHERE { VALUES ?x { 1 } BIND(1 AS ?flag) FILTER EXISTS { FILTER(?flag = 1) } }";
        assertEquals(1, evaluate(compile(query)).size());
        samePull(query);
    }

    @Test void unionFactoringDoesNotExposeHiddenAssignments() {
        samePull("SELECT * WHERE { { SELECT ?y WHERE { BIND(1 AS ?tag) BIND(2 AS ?y) } } UNION { SELECT ?tag WHERE { BIND(1 AS ?tag) } } }");
    }

    @Test void unionFactoringDoesNotRestoreRejectedHiddenVariables() {
        samePull("SELECT * WHERE { { SELECT ?tag ?y WHERE { BIND(1 AS ?tag) BIND(2 AS ?y) } } UNION { SELECT ?tag WHERE { BIND(1 AS ?tag) BIND(3 AS ?y) } } }");
    }

    @Test void lateralPullRetainsLeftHandSideOutputs() {
        samePull("SELECT ?lhs ?rhs WHERE { VALUES ?lhs { 1 2 } LATERAL { SELECT ?rhs WHERE { BIND(3 AS ?rhs) } } }");
    }

    @Test void distinctPlacementDoesNotAssumeCustomFunctionsAreStable() {
        samePlacement("SELECT DISTINCT (<urn:audit:next>() AS ?v) WHERE { VALUES ?x { 1 1 } }");
    }

    @Test void filterPullDoesNotReorderCustomFunctionCalls() {
        samePull("SELECT ?x ?v WHERE { VALUES ?x { 1 2 } BIND(<urn:audit:next>() AS ?v) FILTER(?x > 1) }");
    }

    private static List<Op> variants(Op input) {
        java.util.ArrayList<Op> result = new java.util.ArrayList<>();
        for (List<Var> projection : List.of(List.of(Var.alloc("x")), List.of(Var.alloc("y")),
                List.of(Var.alloc("x"), Var.alloc("y")), List.<Var>of())) {
            result.add(new OpProject(input, projection));
        }
        result.add(OpDistinct.create(input));
        result.add(OpFilter.filter(new E_Bound(new ExprVar("x")), input));
        result.add(OpFilter.filter(new E_Equals(new ExprVar("x"), NodeValue.makeInteger(1)), input));
        result.add(new OpSlice(input, 0, 1));
        result.add(new OpSlice(input, 1, 2));
        if (!OpVars.visibleVars(input).contains(Var.alloc("z"))) {
            result.add(OpExtend.create(input, Var.alloc("z"), NodeValue.makeInteger(7)));
            result.add(OpExtend.create(input, Var.alloc("z"), new E_Add(new ExprVar("x"), NodeValue.makeInteger(1))));
            result.add(OpExtend.create(input, Var.alloc("z"), new E_Divide(NodeValue.makeInteger(1), new ExprVar("x"))));
        }
        result.add(OpUnion.create(input, compile("SELECT * WHERE { VALUES (?x ?y) { (1 2) (1 2) } }")));
        return result;
    }

    @Test void boundedDifferentialAuditOfDeterministicAlgebra() {
        int checked = 0;
        for (String values : List.of("", "(1 2)", "(1 2) (1 2)", "(0 UNDEF) (1 2) (1 3)",
                "(UNDEF 2) (2 UNDEF) (UNDEF UNDEF)")) {
            Op seed = compile("SELECT * WHERE { VALUES (?x ?y) { " + values + " } }");
            for (Op one : variants(seed)) {
                for (Op original : variants(one)) {
                    var expected = evaluate(original);
                    Op pulled = TransformPullExtend.transform(original);
                    assertEquals(expected, evaluate(pulled), "Pull: " + original + " -> " + pulled);
                    Op placed = TransformDistinctPlacement.rewrite(original, true);
                    assertEquals(expected, evaluate(placed), "Placement: " + original + " -> " + placed);
                    Op composed = TransformDistinctPlacement.rewrite(pulled, true);
                    assertEquals(expected, evaluate(composed), "Composed: " + original + " -> " + composed);
                    checked++;
                }
            }
        }
        assertEquals(800, checked, "Keep the finite coverage explicit");
    }

    @Test void theOriginalJoinHoistCouldChangeAnUnboundExpressionIntoABoundOne() {
        Op left = compile("SELECT * WHERE { BIND(?x + 1 AS ?tag) }");
        Op right = compile("SELECT * WHERE { VALUES ?x { 2 } }");
        Op original = OpJoin.create(left, right);
        assertEquals(evaluate(original), evaluate(TransformPullExtend.transform(original)));
        OpExtend extend = (OpExtend)left;
        Op unsafe = OpExtend.create(OpJoin.create(extend.getSubOp(), right), extend.getVarExprList());
        // Before the join, ?x is unbound at the extension; after the join it is 2.
        assertNotEquals(evaluate(original), evaluate(unsafe));
    }

    @Test void unionFactoringPreservesDependenciesOfAssignmentsThatRemainBelow() {
        samePull("SELECT * WHERE { { SELECT ?tag ?y WHERE { VALUES ?x { 1 } BIND(1 AS ?tag) BIND(?tag + ?x AS ?y) } } UNION { SELECT ?tag WHERE { BIND(1 AS ?tag) } } }");
    }

    @Test void lateralWithoutPullableBindingsRetainsItsLeftOutputs() {
        samePull("SELECT ?lhs ?rhs WHERE { VALUES ?lhs { 1 2 } LATERAL { SELECT ?rhs WHERE { BIND(?lhs + 1 AS ?rhs) } } }");
        samePull("SELECT ?lhs ?rhs WHERE { VALUES ?lhs { 1 2 } LATERAL { SELECT ?lhs ?rhs WHERE { BIND(3 AS ?rhs) } } }");
    }
}
