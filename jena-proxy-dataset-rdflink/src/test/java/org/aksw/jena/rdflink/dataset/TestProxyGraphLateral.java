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

package org.aksw.jena.rdflink.dataset;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import org.aksw.jena.sparql.core.remote.DsgSparqlExecutor;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.graph.*;
import org.apache.jena.query.*;
import org.apache.jena.rdflink.*;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.algebra.walker.Walker;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.binding.*;
import org.apache.jena.sparql.engine.main.QueryEngineMain;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.junit.jupiter.api.Test;

/** Graph-routing correlation, separate from standalone UDG semantics. */
class TestProxyGraphLateral {
    private static Node uri(String name) {
        return NodeFactory.createURI("urn:" + name);
    }

    private static final String PATTERN = "GRAPH ?g { ?s ?p ?x . ?x <urn:country> ?c }";

    private record Sent(Node alias, Query query) {}

    private static final class Fixture implements AutoCloseable {
        final DatasetGraph backend = DatasetGraphFactory.create();
        final DatasetGraph expected = DatasetGraphFactory.createGeneral();
        final DatasetGraph composite = DatasetGraphFactory.createGeneral();
        final List<Sent> sent = new ArrayList<>();
        final FusekiServer server;

        Fixture(boolean http) {
            for (String name : List.of("a", "b", "local")) {
                Node graph = name.equals("b") ? Quad.defaultGraphNodeGenerated : uri("remote:" + name);
                for (int i = 0; i < 3; ++i) {
                    backend.add(graph, uri(name + i), uri("p"), uri(name + "x" + i));
                    backend.add(graph, uri(name + "x" + i), uri("country"), uri("countryValue"));
                }
            }
            for (String name : List.of("a", "b", "local")) {
                Node graph = name.equals("b") ? Quad.defaultGraphNodeGenerated : uri("remote:" + name);
                backend.add(graph, uri(name + "0"), uri("alias"), uri(name));
            }
            server = http ? FusekiServer.create().port(0).add("/data", backend).build() : null;
            if (server != null) server.start();
            for (String name : List.of("a", "b", "empty", "local")) {
                Node alias = uri(name);
                Node graph = name.equals("b") ? Quad.defaultGraphNodeGenerated : uri("remote:" + name);
                Graph reference =
                        Quad.isDefaultGraph(graph) ? backend.getDefaultGraph() : backend.getGraph(graph);
                expected.addGraph(alias, reference);
                if (name.equals("local")) composite.addGraph(alias, reference);
                else {
                    DatasetGraphOverRDFLink remote =
                            new DatasetGraphOverRDFLink(DsgSparqlExecutor.DEFAULT, false, false) {
                                @Override
                                public RDFLink newLink() {
                                    RDFLink delegate =
                                            http
                                                    ? RDFLinkHTTP.newBuilder()
                                                            .destination("http://localhost:" + server.getPort() + "/data")
                                                            .build()
                                                    : RDFLink.connect(
                                                            new DatasetGraphWrapper(backend) {
                                                                @Override
                                                                public void close() {}
                                                            });
                                    return (RDFLink)
                                            Proxy.newProxyInstance(
                                                    RDFLink.class.getClassLoader(),
                                                    new Class<?>[] {RDFLink.class},
                                                    (obj, method, args) -> {
                                                        if (method.getName().equals("query") && args[0] instanceof Query query)
                                                            sent.add(new Sent(alias, query.cloneQuery()));
                                                        try {
                                                            return method.invoke(delegate, args);
                                                        } catch (InvocationTargetException e) {
                                                            throw e.getCause();
                                                        }
                                                    });
                                }

                                @Override
                                public Iterator<Quad> find(Node g, Node s, Node p, Node o) {
                                    throw new AssertionError("Dataset.find fallback");
                                }

                                @Override
                                public Iterator<Quad> findNG(Node g, Node s, Node p, Node o) {
                                    throw new AssertionError("Dataset.findNG fallback");
                                }

                                @Override
                                public Graph getGraph(Node graph) {
                                    return new GraphView(this, graph) {
                                        @Override
                                        protected ExtendedIterator<Triple> graphBaseFind(Triple pattern) {
                                            throw new AssertionError("Graph.find fallback");
                                        }
                                    };
                                }
                            };
                    composite.addGraph(alias, remote.getGraph(graph));
                }
            }
        }

        @Override
        public void close() {
            composite.close();
            expected.close();
            if (server != null) server.stop();
            else backend.close();
        }
    }

    private static List<Binding> rows(DatasetGraph data, String query, boolean optimize) {
        try (var exec =
                QueryExec.dataset(data)
                        .query(QueryFactory.create(query, Syntax.syntaxARQ))
                        .context(Context.create().set(ARQ.optimization, optimize))
                        .build()) {
            List<Binding> result = new ArrayList<>();
            exec.select().forEachRemaining(b -> result.add(BindingFactory.copy(b)));
            return result;
        }
    }

    private static Map<Binding, Integer> bag(List<Binding> rows) {
        Map<Binding, Integer> result = new HashMap<>();
        rows.forEach(b -> result.merge(b, 1, Integer::sum));
        return result;
    }

    private static List<String> queries() {
        return List.of(
                "SELECT DISTINCT ?g ?s {GRAPH ?g {} LATERAL {SELECT DISTINCT ?g ?s {"
                        + PATTERN
                        + "} LIMIT 10}}",
                "SELECT DISTINCT ?g ?s {GRAPH ?g {} LATERAL {SELECT DISTINCT ?g ?s {"
                        + PATTERN
                        + "} ORDER BY ?s LIMIT 2 OFFSET 1}}",
                "SELECT DISTINCT ?g ?s {BIND(<urn:a0> AS ?s) GRAPH ?g {} LATERAL {SELECT DISTINCT ?g ?s {"
                        + PATTERN
                        + "} LIMIT 10}}",
                "SELECT ?g ?s {VALUES ?g {<urn:a> <urn:a> <urn:b>} LATERAL {SELECT DISTINCT ?g ?s {"
                        + PATTERN
                        + "} ORDER BY ?s LIMIT 1}}",
                "SELECT ?g ?s {GRAPH ?g {} LATERAL {SELECT DISTINCT ?s {"
                        + PATTERN
                        + "} ORDER BY ?s LIMIT 1}}",
                "SELECT DISTINCT ?g ?s {GRAPH ?g {} LATERAL {SELECT DISTINCT ?g ?s {LATERAL {"
                        + PATTERN
                        + "}} LIMIT 10}}",
                "SELECT DISTINCT ?g ?s {GRAPH ?g {} LATERAL {SELECT ?g ?s {GRAPH ?g {?s <urn:p> ?x} FILTER EXISTS {GRAPH ?g {?x <urn:country> ?c}}} ORDER BY ?s LIMIT 1}}");
    }

    @Test
    void correlationSurvivesSubqueriesLimitsRepeatedInputsAndNestedLateral() {
        for (boolean http : List.of(false, true))
            try (Fixture f = new Fixture(http)) {
                for (boolean optimize : List.of(false, true))
                    for (String query : queries()) {
                        f.sent.clear();
                        assertEquals(
                                bag(rows(f.expected, query, optimize)),
                                bag(rows(f.composite, query, optimize)),
                                "http=" + http + " optimize=" + optimize + " " + query);
                    }
            }
    }

    @Test
    void previouslyWorkingUnboundConstantAndNonLateralPatternsKeepTheirResultBags() {
        List<String> queries =
                List.of(
                        "SELECT ?g ?s {LATERAL {SELECT DISTINCT ?g ?s {"
                                + PATTERN
                                + "} ORDER BY ?g ?s LIMIT 20}}",
                        "SELECT ?tag ?g ?s {VALUES ?tag {1 1 2} LATERAL {SELECT DISTINCT ?tag ?g ?s {"
                                + PATTERN
                                + "} ORDER BY ?g ?s LIMIT 2 OFFSET 1}}",
                        "SELECT ?g ?s {VALUES ?g {UNDEF} LATERAL {SELECT DISTINCT ?g ?s {"
                                + PATTERN
                                + "} ORDER BY ?g ?s LIMIT 20}}",
                        "SELECT ?s {VALUES ?tag {1 1} LATERAL {SELECT ?s {GRAPH <urn:a> {?s <urn:p> ?x}} ORDER BY ?s LIMIT 2}}",
                        "SELECT ?g ?s {GRAPH ?g {?s <urn:alias> ?g}}",
                        "SELECT ?g ?s {VALUES ?tag {1 1} LATERAL {SELECT ?g ?s {GRAPH ?g {?s <urn:alias> ?g}}}}");
        for (boolean http : List.of(false, true))
            try (Fixture f = new Fixture(http)) {
                for (boolean optimize : List.of(false, true))
                    for (String query : queries) {
                        assertEquals(
                                bag(rows(f.expected, query, optimize)),
                                bag(rows(f.composite, query, optimize)),
                                "http=" + http + " optimize=" + optimize + " " + query);
                    }
            }
    }

    @Test
    void selectorsDoNotRequestOtherGraphPatternsAndKeepWholeBgpsRemote() {
        for (boolean optimize : List.of(false, true))
            try (Fixture f = new Fixture(false)) {
                String query =
                        "SELECT ?g ?s {VALUES ?g {<urn:b>} LATERAL {SELECT ?g ?s {" + PATTERN + "} LIMIT 10}}";
                var actual = rows(f.composite, query, optimize);
                assertEquals(3, actual.size());
                assertEquals(1, f.sent.size(), f.sent.toString());
                assertEquals(uri("b"), f.sent.get(0).alias());
                assertTrue(f.sent.get(0).query().toString().contains("<urn:country>"));
                assertTrue(
                        f.sent.get(0).query().getGraphURIs().isEmpty(),
                        "alias b addresses the remote default graph");
            }
    }

    @Test
    void requestInitialBindingsRetainGraphAndSubjectCorrelation() {
        try (Fixture f = new Fixture(false)) {
            String query = queries().get(0);
            Binding input = BindingFactory.binding(Var.alloc("g"), uri("b"), Var.alloc("s"), uri("b1"));
            Query parsed = QueryFactory.create(query, Syntax.syntaxARQ);
            for (boolean optimize : List.of(false, true)) {
                Context context = Context.create().set(ARQ.optimization, optimize);
                var referencePlan =
                        new QueryEngineMain(parsed, f.expected, input, context.copy()).getPlan();
                var actualPlan =
                        new QueryEngineFactoryProxyGraphs().create(parsed, f.composite, input, context.copy());
                try {
                    List<Binding> expected = new ArrayList<>(), actual = new ArrayList<>();
                    referencePlan.iterator().forEachRemaining(b -> expected.add(BindingFactory.copy(b)));
                    actualPlan.iterator().forEachRemaining(b -> actual.add(BindingFactory.copy(b)));
                    assertEquals(bag(expected), bag(actual));
                    assertEquals(1, actual.size());
                } finally {
                    referencePlan.close();
                    actualPlan.close();
                }
            }
        }
    }

    private static int graphTables(Op root) {
        int[] result = {0};
        Walker.walkSkipService(
                root,
                new OpVisitorBase() {
                    @Override
                    public void visit(OpTable table) {
                        if (table.getTable().getVars().contains(Var.alloc("g"))) ++result[0];
                    }
                },
                null,
                null,
                null);
        return result[0];
    }

    private static int variableGraphs(Op root) {
        int[] result = {0};
        Walker.walkSkipService(
                root,
                new OpVisitorBase() {
                    @Override
                    public void visit(OpGraph graph) {
                        if (graph.getNode().isVariable()) ++result[0];
                    }
                },
                null,
                null,
                null);
        return result[0];
    }

    @Test
    void onlyRightOperandsAreProtectedEvenWhenTheSameOpObjectIsReused() {
        Op graph =
                new OpGraph(Var.alloc("g"), Algebra.compile(QueryFactory.create("SELECT * {?s ?p ?o}")));
        Op input = OpLateral.create(graph, graph);
        OpLateral result =
                (OpLateral)
                        TransformProxyGraphRouting.rewrite(
                                input, List.of(uri("a")), Map.of(uri("a"), uri("route:a")));
        assertEquals(1, graphTables(result.getLeft()));
        assertEquals(0, variableGraphs(result.getLeft()));
        assertEquals(0, graphTables(result.getRight()));
        assertEquals(1, variableGraphs(result.getRight()));
        OpService service = new OpService(uri("external"), graph, false);
        OpLateral opaque =
                (OpLateral)
                        TransformProxyGraphRouting.rewrite(
                                OpLateral.create(graph, service),
                                List.of(uri("a")),
                                Map.of(uri("a"), uri("route:a")));
        assertSame(service, opaque.getRight());
    }

    @Test
    void quadRoutingUsesTheSameCorrelatedSelectors() {
        var query = Algebra.compile(QueryFactory.create(queries().get(0), Syntax.syntaxARQ));
        Op quadQuery = Algebra.toQuadForm(query);
        Op result =
                TransformProxyGraphRouting.rewrite(
                        quadQuery, List.of(uri("a")), Map.of(uri("a"), uri("route:a")));
        assertTrue(variableGraphs(result) > 0);
        try (Fixture f = new Fixture(false)) {
            var plan =
                    new QueryEngineFactoryProxyGraphs()
                            .create(quadQuery, f.composite, BindingFactory.empty(), Context.create());
            try {
                List<Binding> actual = new ArrayList<>();
                plan.iterator().forEachRemaining(b -> actual.add(BindingFactory.copy(b)));
                assertEquals(bag(rows(f.expected, queries().get(0), true)), bag(actual));
            } finally {
                plan.close();
            }
        }
    }
}
