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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.*;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.expr.*;
import org.apache.jena.sparql.expr.aggregate.*;
import org.apache.jena.sparql.service.ServiceExecutorRegistry;
import org.junit.jupiter.api.Test;

public class TestAggregationTransforms {
    private static Op compile(String query) {
        return Algebra.compile(QueryFactory.create(query));
    }

    private static Op split(Op op, boolean numeric) {
        return Transformer.transformSkipService(
                new TransformGroupUnion(
                        op,
                        numeric
                                ? AggregateDecompositionRegistry.withNumericReassociation()
                                : AggregateDecompositionRegistry.counts()),
                op);
    }

    /** Compare bags including RDF datatypes and unbound cells; never compare just row counts. */
    private static Map<Binding, Integer> evaluate(Op op) {
        DatasetGraph dataset = DatasetGraphFactory.create();
        try {
            Map<Binding, Integer> rows = new HashMap<>();
            QueryIterator iterator = Algebra.exec(op, dataset);
            try {
                while (iterator.hasNext()) {
                    rows.merge(BindingFactory.copy(iterator.nextBinding()), 1, Integer::sum);
                }
            } finally {
                iterator.close();
            }
            return rows;
        } finally {
            dataset.close();
        }
    }

    private static void equivalent(String query, boolean numeric) {
        Op original = compile(query);
        Op rewritten = split(original, numeric);
        assertNotEquals(original, rewritten, "Expected a split");
        assertEquals(evaluate(original), evaluate(rewritten), rewritten.toString());
    }

    @Test
    public void countAcrossNestedUnionPreservesDuplicatesAndUnboundValues() {
        equivalent(
                "SELECT (COUNT(*) AS ?all) (COUNT(?x) AS ?bound) WHERE { "
                        + "{ VALUES ?x { 1 1 UNDEF } } UNION { VALUES ?x { 1 2 } } "
                        + "UNION { VALUES ?x {} } }",
                false);
    }

    @Test
    public void groupedCountMergesSharedKeysAndKeepsMissingKeys() {
        equivalent(
                "SELECT ?k (COUNT(*) AS ?n) (COUNT(?x) AS ?bound) WHERE { "
                        + "{ VALUES (?k ?x) { (1 2) (1 2) (UNDEF UNDEF) } } UNION "
                        + "{ VALUES (?k ?x) { (1 3) (2 UNDEF) (UNDEF 4) } } } GROUP BY ?k",
                false);
    }

    @Test
    public void emptyInputHasOneGlobalCountButNoKeyedGroups() {
        equivalent("SELECT (COUNT(*) AS ?n) WHERE { { VALUES ?x {} } UNION { VALUES ?x {} } }", false);
        equivalent(
                "SELECT ?x (COUNT(*) AS ?n) WHERE { { VALUES ?x {} } UNION { VALUES ?x {} } } GROUP BY ?x",
                false);
    }

    @Test
    public void sumAndAverageUseWeightedPartialState() {
        equivalent(
                "SELECT (SUM(?x) AS ?s) (AVG(?x) AS ?a) WHERE { "
                        + "{ VALUES ?x { 1 3 } } UNION { VALUES ?x { 8 } } }",
                true);
        equivalent(
                "SELECT ?k (SUM(?x) AS ?s) (AVG(?x) AS ?a) WHERE { "
                        + "{ VALUES (?k ?x) { (1 1) (1 3) (2 7) } } UNION "
                        + "{ VALUES (?k ?x) { (1 8) (2 9) } } } GROUP BY ?k",
                true);
    }

    @Test
    public void numericErrorsAndEmptyPartitionsRemainObservable() {
        for (String values : List.of("UNDEF", "\"bad\"", "1 UNDEF", "1 \"bad\"")) {
            equivalent(
                    "SELECT (SUM(?x) AS ?s) (AVG(?x) AS ?a) WHERE { "
                            + "{ VALUES ?x { "
                            + values
                            + " } } UNION { VALUES ?x { 2 } } }",
                    true);
            equivalent(
                    "SELECT (AVG(?x) AS ?a) WHERE { "
                            + "{ VALUES ?x { "
                            + values
                            + " } } UNION { VALUES ?x {} } }",
                    true);
        }
        equivalent(
                "SELECT (SUM(?x) AS ?s) (AVG(?x) AS ?a) WHERE { "
                        + "{ VALUES ?x {} } UNION { VALUES ?x {} } }",
                true);
        equivalent(
                "SELECT (AVG(?x) AS ?a) WHERE { " + "{ VALUES ?x {} } UNION { VALUES ?x { 2 4 } } }", true);
    }

    @Test
    public void unsupportedAggregatesLeaveEntireGroupAlone() {
        for (String expr :
                List.of(
                        "COUNT(DISTINCT ?x)",
                        "COUNT(DISTINCT *)",
                        "GROUP_CONCAT(?x)",
                        "COUNT(RAND())",
                        "COUNT(1 / ?x)",
                        "SUM(?x)")) {
            Op op =
                    compile(
                            "SELECT (COUNT(*) AS ?n) ("
                                    + expr
                                    + " AS ?a) WHERE { "
                                    + "{ VALUES ?x { 1 } } UNION { VALUES ?x { 2 } } }");
            assertEquals(op, split(op, false));
        }
        Op keys =
                compile(
                        "SELECT ?k (COUNT(*) AS ?n) WHERE { "
                                + "{ VALUES ?x { 1 } } UNION { VALUES ?x { 2 } } } GROUP BY (?x + 1 AS ?k)");
        assertEquals(keys, split(keys, false));
    }

    @Test
    public void temporaryVariablesCannotCaptureAnOuterVariable() {
        Op op =
                compile(
                        "SELECT ?__jenaProxyAgg0 ?n WHERE { VALUES ?__jenaProxyAgg0 { 99 } "
                                + "{ SELECT (COUNT(*) AS ?n) WHERE { { VALUES ?x { 1 } } UNION { VALUES ?x { 2 } } } } }");
        assertEquals(evaluate(op), evaluate(split(op, false)));
    }

    private static Op pull(Op root) {
        return Transformer.transformSkipService(new TransformPullExtend(root), root);
    }

    @Test
    public void pullConstantGroupKeyPreservesEmptyAndNonemptyGroups() {
        for (String rows : List.of("", "UNDEF", "1 1 2")) {
            Op op =
                    compile(
                            "SELECT ?g (COUNT(?x) AS ?c) WHERE { VALUES ?x { "
                                    + rows
                                    + " } BIND(<urn:g> AS ?g) } GROUP BY ?g");
            assertNotEquals(op, pull(op));
            assertEquals(evaluate(op), evaluate(pull(op)));
        }
    }

    @Test
    public void pullConstantAlongsideOtherKeysAndDropUnusedAssignments() {
        for (String keys : List.of("?g ?x", "?x", "")) {
            String selected = keys.isEmpty() ? "" : keys + " ";
            Op op =
                    compile(
                            "SELECT "
                                    + selected
                                    + "(COUNT(*) AS ?c) WHERE { VALUES ?x { 1 1 2 } "
                                    + "BIND(<urn:g> AS ?g) } "
                                    + (keys.isEmpty() ? "" : "GROUP BY " + keys));
            assertNotEquals(op, pull(op));
            assertEquals(evaluate(op), evaluate(pull(op)));
        }
    }

    @Test
    public void dependentAndNonconstantExtensionsAreLeftAlone() {
        for (String query :
                List.of(
                        "SELECT ?g (COUNT(?g) AS ?c) WHERE { VALUES ?x { 1 } BIND(1 AS ?g) } GROUP BY ?g",
                        "SELECT ?g (COUNT(*) AS ?c) WHERE { VALUES ?x { 1 } BIND(?x + 1 AS ?g) } GROUP BY ?g",
                        "SELECT ?g (COUNT(DISTINCT *) AS ?c) WHERE { VALUES ?x { 1 } BIND(1 AS ?g) } GROUP BY ?g")) {
            Op op = compile(query);
            assertEquals(op, pull(op));
        }
    }

    private static List<OpService> services(Op op) {
        List<OpService> result = new ArrayList<>();
        OpWalker.walk(
                op,
                new OpVisitorBase() {
                    @Override
                    public void visit(OpService service) {
                        result.add(service);
                    }
                });
        return result;
    }

    private static Op delegate(Op root) {
        return Transformer.transformSkipService(new TransformGroupService(s -> true, g -> true), root);
    }

    @Test
    public void servicePushdownDelegatesDistinctWithoutSplitting() {
        Op op =
                compile(
                        "SELECT (COUNT(DISTINCT ?x) AS ?n) WHERE { "
                                + "SERVICE <urn:test:service> { VALUES ?x { 1 1 2 } } }");
        Op rewritten = delegate(op);
        assertInstanceOf(OpGroup.class, services(rewritten).get(0).getSubOp());
        assertEquals(rewritten, delegate(rewritten));
        Op rejected =
                Transformer.transformSkipService(new TransformGroupService(s -> false, g -> true), op);
        assertEquals(op, rejected);
        assertEquals(
                op, Transformer.transformSkipService(new TransformGroupService(s -> true, g -> false), op));
    }

    @Test
    public void silentAndVariableServicesAreNotMoved() {
        for (String service : List.of("SERVICE SILENT <urn:test:service>", "SERVICE ?endpoint")) {
            Op op = compile("SELECT (COUNT(*) AS ?n) WHERE { " + service + " { ?s ?p ?o } }");
            assertEquals(op, delegate(op));
        }
    }

    @Test
    public void independentRulesComposeAndSendOnlyGroupsToServices() {
        for (String keys : List.of("", "?g")) {
            Op original =
                    compile(
                            "SELECT "
                                    + keys
                                    + " (COUNT(*) AS ?n) WHERE { "
                                    + "{ SERVICE <urn:test:a> { VALUES ?x { 1 1 } } BIND(<urn:g1> AS ?g) } UNION "
                                    + "{ SERVICE <urn:test:b> { VALUES ?x {} } BIND(<urn:g2> AS ?g) } } "
                                    + (keys.isEmpty() ? "" : "GROUP BY " + keys));
            Op rewritten = delegate(pull(split(original, false)));
            assertEquals(2, services(rewritten).size());
            services(rewritten).forEach(s -> assertInstanceOf(OpGroup.class, s.getSubOp()));
            // A query-local executor emulates independent endpoints; no HTTP or network is used.
            DatasetGraph dataset = DatasetGraphFactory.create();
            AtomicInteger requests = new AtomicInteger();
            ServiceExecutorRegistry registry = new ServiceExecutorRegistry();
            registry.add(
                    (execute, unused, parent, context) -> {
                        requests.incrementAndGet();
                        assertInstanceOf(OpGroup.class, execute.getSubOp());
                        return QC.execute(execute.getSubOp(), parent, context);
                    });
            ServiceExecutorRegistry.set(dataset.getContext(), registry);
            Map<Binding, Integer> actual = new HashMap<>();
            try {
                QueryIterator it = Algebra.exec(rewritten, dataset);
                try {
                    while (it.hasNext()) {
                        actual.merge(BindingFactory.copy(it.nextBinding()), 1, Integer::sum);
                    }
                } finally {
                    it.close();
                }
            } finally {
                dataset.close();
            }
            assertEquals(2, requests.get());
            Op local =
                    Transformer.transform(
                            new TransformCopy() {
                                @Override
                                public Op transform(OpService service, Op child) {
                                    return child;
                                }
                            },
                            original);
            assertEquals(evaluate(local), actual);
            // Query serialization is also needed by the proxy's RDFLink dispatcher.
            assertEquals(
                    evaluate(local),
                    evaluate(
                            Transformer.transform(
                                    new TransformCopy() {
                                        @Override
                                        public Op transform(OpService service, Op child) {
                                            return child;
                                        }
                                    },
                                    Algebra.compile(OpAsQuery.asQuery(rewritten)))));
        }
    }

    @Test
    public void consolidatedPullExtendPreservesBagsAcrossItsOriginalRules() {
        for (String query :
                List.of(
                        "SELECT * WHERE { { VALUES ?x { 1 1 } BIND(3 AS ?tag) } UNION { VALUES ?x { 2 } BIND(3 AS ?tag) } }",
                        "SELECT * WHERE { { VALUES ?x { 1 } BIND(3 AS ?tag) } UNION { VALUES ?x { 2 } BIND(4 AS ?tag) } }",
                        "SELECT DISTINCT ?tag WHERE { VALUES ?x { 1 1 2 } BIND(3 AS ?tag) }",
                        "SELECT DISTINCT ?tag WHERE { VALUES ?x {} BIND(3 AS ?tag) }",
                        "SELECT DISTINCT ?tag ?derived WHERE { VALUES ?x { 1 2 } BIND(3 AS ?tag) BIND(?tag + ?x AS ?derived) }",
                        "SELECT * WHERE { VALUES ?x { 1 2 } BIND(3 AS ?tag) FILTER(?x > 1) }",
                        "SELECT * WHERE { VALUES ?x { 1 2 } BIND(3 AS ?tag) } LIMIT 1",
                        "SELECT * WHERE { { VALUES ?x { 1 2 } BIND(3 AS ?tag) } { VALUES ?y { 5 6 } } }",
                        "SELECT * WHERE { { VALUES ?x { 1 2 } BIND(3 AS ?tag) } { VALUES ?tag { 4 } } }",
                        "SELECT * WHERE { { VALUES ?x { 1 2 } } { BIND(?x + 1 AS ?tag) } }")) {
            Op original = compile(query);
            assertEquals(evaluate(original), evaluate(pull(original)), query);
        }
    }

    @Test
    public void disjunctionPullRetainsEveryMemberAndSplittingSupportsNaryUnions() {
        OpDisjunction disjunction = OpDisjunction.create();
        Var x = Var.alloc("x");
        Var tag = Var.alloc("tag");
        for (int value = 1; value <= 3; value++) {
            disjunction.add(
                    OpExtend.create(
                            OpExtend.create(OpTable.unit(), x, NodeValue.makeInteger(value)),
                            tag,
                            NodeValue.makeInteger(7)));
        }
        assertEquals(evaluate(disjunction), evaluate(pull(disjunction)));
        Op group =
                new OpGroup(
                        disjunction,
                        new VarExprList(),
                        List.of(new ExprAggregator(Var.alloc("c"), new AggCount())));
        assertNotEquals(group, split(group, false));
        assertEquals(evaluate(group), evaluate(split(group, false)));
    }

    @Test
    public void existingServiceBodiesAreOpaqueToTheLocalPlanningPasses() {
        Op op =
                compile(
                        "SELECT * WHERE { SERVICE <urn:test:service> { "
                                + "SELECT (COUNT(*) AS ?n) WHERE { { VALUES ?x { 1 } } UNION { VALUES ?x { 2 } } } } }");
        assertEquals(op, split(op, false));
        assertEquals(op, pull(op));
    }

    @Test
    public void bareGroupSerializationRetainsItsAggregateExpressions() {
        Op group =
                new OpGroup(
                        compile("SELECT * WHERE { VALUES ?x { 1 1 2 } }"),
                        new VarExprList(),
                        List.of(new ExprAggregator(Var.alloc("count"), new AggCount())));
        var query = AlgebraQueries.asQuery(group);
        assertEquals(evaluate(group), evaluate(Algebra.compile(QueryFactory.create(query.toString()))));
    }

    @Test
    public void singletonTablesNormalizeOnlyBelowGroupsWithIndependentVariables() {
        for (String query :
                List.of(
                        "SELECT ?g (COUNT(*) AS ?c) WHERE { VALUES ?g { <urn:g> } VALUES ?x { 1 2 } } GROUP BY ?g",
                        "SELECT ?g (COUNT(*) AS ?c) WHERE { VALUES ?g { <urn:g> } VALUES ?x {} } GROUP BY ?g")) {
            Op original = compile(query);
            Op normalized = Transformer.transformSkipService(new TransformGroupTable(), original);
            assertNotEquals(original, normalized);
            assertEquals(evaluate(original), evaluate(normalized));
        }
        for (String query :
                List.of(
                        "SELECT (COUNT(*) AS ?c) WHERE { VALUES ?x { 1 } VALUES ?x { 2 } }",
                        "SELECT (COUNT(*) AS ?c) WHERE { VALUES ?g { UNDEF } VALUES ?x { 2 } }",
                        "SELECT * WHERE { VALUES ?g { <urn:g> } VALUES ?x { 2 } }")) {
            Op original = compile(query);
            Op normalized = Transformer.transformSkipService(new TransformGroupTable(), original);
            assertEquals(evaluate(original), evaluate(normalized));
        }
    }

    private static Map<Binding, Integer> remoteRoundTrip(Op original) {
        var prepared = AlgebraQueries.prepareForRemote(original);
        Op parsed = Algebra.compile(QueryFactory.create(prepared.query().toString()));
        Map<Binding, Integer> restored = new HashMap<>();
        evaluate(parsed)
                .forEach(
                        (binding, multiplicity) -> {
                            var builder = BindingBuilder.create();
                            prepared
                                    .outputMapping()
                                    .forEach(
                                            (remote, local) -> {
                                                if (binding.contains(remote)) builder.add(local, binding.get(remote));
                                            });
                            restored.merge(builder.build(), multiplicity, Integer::sum);
                        });
        return restored;
    }

    @Test
    public void wireAliasesPreserveAllocatedAggregateOutputsAndDistinctScopes() {
        for (String name : List.of(".0", "/.0")) {
            Op group =
                    new OpGroup(
                            compile("SELECT * WHERE { VALUES ?x { 1 1 2 } }"),
                            new VarExprList(),
                            List.of(new ExprAggregator(Var.alloc(name), new AggCount())));
            assertEquals(evaluate(group), remoteRoundTrip(group));
        }
        Op scoped =
                OpExtend.create(
                        OpExtend.create(OpTable.unit(), Var.alloc("/x"), NodeValue.makeInteger(1)),
                        Var.alloc("//x"),
                        NodeValue.makeInteger(2));
        assertEquals(evaluate(scoped), remoteRoundTrip(scoped));
    }
}
