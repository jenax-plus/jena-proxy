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
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.binding.*;
import org.junit.jupiter.api.Test;

class TestDistinctTransforms {
    private static Op compile(String query) {
        return Algebra.compile(QueryFactory.create(query));
    }

    private static Map<Binding, Integer> evaluate(Op op) {
        DatasetGraph dataset = DatasetGraphFactory.create();
        Map<Binding, Integer> result = new HashMap<>();
        var iterator = Algebra.exec(op, dataset);
        try {
            while (iterator.hasNext()) result.merge(BindingFactory.copy(iterator.nextBinding()), 1, Integer::sum);
        } finally {
            iterator.close();
            dataset.close();
        }
        return result;
    }

    private static int distincts(Op op) {
        AtomicInteger count = new AtomicInteger();
        OpWalker.walk(op, new OpVisitorBase() {
            @Override public void visit(OpDistinct distinct) { count.incrementAndGet(); }
        });
        return count.get();
    }

    private static Op distribute(Op op) {
        return Transformer.transformSkipService(new TransformDistinctUnion(), op);
    }

    @Test void unionDistributionRetainsGlobalDeduplicationAndIsIdempotent() {
        Op original = compile("SELECT DISTINCT ?x WHERE { { VALUES ?x { 1 1 2 } } UNION { VALUES ?x { 1 2 3 } } }");
        Op rewritten = distribute(original);
        assertEquals(3, distincts(rewritten));
        assertEquals(evaluate(original), evaluate(rewritten));
        assertEquals(rewritten, distribute(rewritten));
    }

    @Test void projectedDistinctUsesTheSameKeyInEveryPartition() {
        Op original = compile("SELECT DISTINCT ?x WHERE { "
            + "{ VALUES (?x ?hidden) { (1 1) (1 2) (UNDEF 1) } } UNION "
            + "{ VALUES (?x ?hidden) { (1 3) (2 4) (UNDEF 2) } } }");
        Op rewritten = distribute(original);
        assertEquals(3, distincts(rewritten));
        assertEquals(evaluate(original), evaluate(rewritten));
        assertEquals(3, evaluate(rewritten).size());
    }

    @Test void nestedAndNaryUnionsRetainAllMembers() {
        OpDisjunction union = OpDisjunction.create();
        for (int x = 1; x <= 3; x++) union.add(compile("SELECT ?x WHERE { VALUES ?x { " + x + " " + x + " } }"));
        Op original = OpDistinct.create(union);
        Op rewritten = distribute(original);
        assertEquals(4, distincts(rewritten));
        assertEquals(evaluate(original), evaluate(rewritten));
        assertEquals(rewritten, distribute(rewritten));
        Op nested = compile("SELECT DISTINCT * WHERE { { VALUES ?x { 1 1 } } UNION { { VALUES ?x { 2 2 } } UNION { VALUES ?x { 3 3 } } } }");
        assertEquals(evaluate(nested), evaluate(distribute(nested)));
    }

    @Test void emptyBranchesAndZeroColumnProjectionsKeepTheirCardinality() {
        for (String members : List.of("{ VALUES ?x {} } UNION { VALUES ?x {} }",
                "{ VALUES ?x {} } UNION { VALUES ?x { 1 1 } }")) {
            Op union = compile("SELECT * WHERE { " + members + " }");
            Op original = OpDistinct.create(new OpProject(union, List.of()));
            assertEquals(evaluate(original), evaluate(distribute(original)));
            assertEquals(evaluate(original), evaluate(TransformDistinctPlacement.rewrite(original, true)));
        }
    }

    @Test void distributionIsOptionalAndPlacementRemainsSeparate() {
        Op original = compile("SELECT DISTINCT ?x WHERE { { VALUES ?x { 1 1 } } UNION { VALUES ?x { 1 2 } } }");
        Op placementOnly = TransformDistinctPlacement.rewrite(original);
        assertEquals(1, distincts(placementOnly));
        assertEquals(placementOnly, TransformDistinctPlacement.rewrite(original, false));
        Op distributed = TransformDistinctPlacement.rewrite(original, true);
        assertEquals(3, distincts(distributed));
        assertEquals(evaluate(original), evaluate(distributed));
    }

    @Test void placementCanExposeAUnionBelowAStableFilter() {
        Op original = compile("SELECT DISTINCT ?x WHERE { { VALUES ?x { 1 1 } } UNION { VALUES ?x { 1 2 } } FILTER(?x > 0) }");
        assertEquals(1, distincts(distribute(original)));
        Op rewritten = TransformDistinctPlacement.rewrite(original, true);
        assertEquals(3, distincts(rewritten));
        assertEquals(evaluate(original), evaluate(rewritten));
    }

    @Test void volatileFiltersAndSlicesRemainPlacementBarriers() {
        Op volatileQuery = compile("SELECT DISTINCT ?x WHERE { { VALUES ?x { 1 1 } } UNION { VALUES ?x { 1 2 } } FILTER(RAND() >= 0) }");
        assertEquals(1, distincts(TransformDistinctPlacement.rewrite(volatileQuery, true)));
        Op sliced = compile("SELECT DISTINCT ?x WHERE { { SELECT ?x WHERE { VALUES ?x { 1 1 2 } } LIMIT 2 } UNION { VALUES ?x { 3 3 } } }");
        assertEquals(evaluate(sliced), evaluate(TransformDistinctPlacement.rewrite(sliced, true)));
        // Removing duplicates before the limit would incorrectly expose value 2.
        assertEquals(2, evaluate(TransformDistinctPlacement.rewrite(sliced, true)).size());
    }

    @Test void queriesWithoutDistinctKeepTheirMultiplicities() {
        Op original = compile("SELECT ?x WHERE { { VALUES ?x { 1 1 } } UNION { VALUES ?x { 1 2 } } }");
        assertEquals(original, TransformDistinctPlacement.rewrite(original, true));
        assertEquals(original, distribute(original));
    }

    @Test void servicePushdownIncludesTheDistinctProjection() {
        Op original = compile("SELECT DISTINCT ?x WHERE { SERVICE <urn:test> { VALUES (?x ?y) { (1 1) (1 2) } } }");
        Op placed = TransformDistinctPlacement.rewrite(original);
        Op rewritten = Transformer.transformSkipService(new TransformDistinctService(s -> true), placed);
        AtomicInteger delegated = new AtomicInteger();
        Op local = Transformer.transform(new TransformCopy() {
            @Override public Op transform(OpService service, Op body) {
                assertInstanceOf(OpDistinct.class, body);
                var prepared = AlgebraQueries.prepareForRemote(body);
                assertTrue(prepared.query().isDistinct());
                assertEquals(1, prepared.query().getResultVars().size());
                delegated.incrementAndGet();
                return body;
            }
        }, rewritten);
        assertEquals(1, delegated.get());
        assertEquals(1, evaluate(local).size());
        assertEquals(rewritten, Transformer.transformSkipService(new TransformDistinctService(s -> true), rewritten));
    }

    @Test void serviceEligibilityAndExistingRemoteBodiesAreRespected() {
        for (String target : List.of("SERVICE SILENT <urn:test>", "SERVICE ?endpoint")) {
            Op original = compile("SELECT DISTINCT ?x WHERE { " + target + " { ?x ?p ?o } }");
            Op placed = TransformDistinctPlacement.rewrite(original);
            assertEquals(placed, Transformer.transformSkipService(new TransformDistinctService(s -> true), placed));
        }
        Op remote = compile("SELECT * WHERE { SERVICE <urn:test> { SELECT DISTINCT ?x WHERE { { VALUES ?x { 1 } } UNION { VALUES ?x { 2 } } } } }");
        assertEquals(remote, TransformDistinctPlacement.rewrite(remote, true));
        Op original = compile("SELECT DISTINCT ?x WHERE { SERVICE <urn:test> { ?x ?p ?o } }");
        Op placed = TransformDistinctPlacement.rewrite(original);
        assertEquals(placed, Transformer.transformSkipService(new TransformDistinctService(s -> false), placed));
    }

    @Test void computedKeysAndUnprojectedFilterDependenciesRemainCorrect() {
        for (String query : List.of(
                "SELECT DISTINCT (?x - ?x AS ?z) WHERE { { VALUES ?x { 1 1 } } UNION { VALUES ?x { 2 3 } } }",
                "SELECT DISTINCT ?x WHERE { { VALUES (?x ?y) { (1 0) (1 1) } } UNION { VALUES (?x ?y) { (2 1) (2 0) } } FILTER(?y = 1) }",
                "SELECT DISTINCT (COUNT(*) AS ?c) WHERE { { VALUES ?x { 1 1 } } UNION { VALUES ?x { 1 2 } } }")) {
            Op original = compile(query);
            assertEquals(evaluate(original), evaluate(TransformDistinctPlacement.rewrite(original, true)), query);
        }
    }
}
