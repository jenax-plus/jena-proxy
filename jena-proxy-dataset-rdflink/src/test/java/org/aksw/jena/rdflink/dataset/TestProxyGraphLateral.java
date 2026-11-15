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
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.sparql.expr.NodeValue;
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

    @Test
    void countryLateralGroupedPropertyPathMatchesSuppliedBaciData() {
        String query = """
                SELECT * WHERE {
                  ?country a <https://schema.projekt-provider.de/global#Country>
                  LATERAL {
                    SELECT ?g ?country (COUNT(*) AS ?c) {
                      GRAPH ?g {
                        ?s <https://schema.projekt-provider.de/baci#importer>/<https://schema.projekt-provider.de/global#hasCountry> ?country
                      }
                    } GROUP BY ?g ?country
                  }
                }
                """;
        Node graph = uri("service:baci");
        Binding expected = BindingBuilder.create()
                .add(Var.alloc("g"), graph)
                .add(Var.alloc("country"), NodeFactory.createURI("https://data.projekt-provider.de/country/BRA"))
                .add(Var.alloc("c"), NodeValue.makeInteger(10).asNode())
                .build();
        for (boolean http : List.of(false, true))
            for (boolean unionDefault : List.of(false, true)) {
                DatasetGraph countries = DatasetGraphFactory.create();
                DatasetGraph baci = DatasetGraphFactory.create();
                DatasetGraph reference = DatasetGraphFactory.createGeneral();
                DatasetGraph composite = DatasetGraphFactory.createGeneral();
                RDFDataMgr.read(countries, "lateral-country/part1.trig");
                RDFDataMgr.read(baci, "lateral-country/part2.trig");
                FusekiServer server = http ? FusekiServer.create().port(0)
                        .add("/countries", countries).add("/baci", baci).build() : null;
                if (server != null) server.start();
                try {
                    // Separate datasets/endpoints: the country source exposes its default
                    // graph, while the BACI source exposes its named graph.
                    composite.addGraph(graph, countryDataProxy(baci, server, "/baci").getGraph(graph));
                    reference.addGraph(graph, baci.getGraph(graph));
                    countries.getDefaultGraph().find().forEachRemaining(reference.getDefaultGraph()::add);
                    if (unionDefault) {
                        Node staticGraph = uri("service:static");
                        composite.addGraph(staticGraph, countryDataProxy(countries, server, "/countries").getDefaultGraph());
                        composite.getContext().set(ProxyDatasetSettings.UNION_DEFAULT_GRAPH, true);
                        reference.addGraph(staticGraph, countries.getDefaultGraph());
                        baci.getGraph(graph).find().forEachRemaining(reference.getDefaultGraph()::add);
                        assertTrue(composite.getDefaultGraph().isEmpty());
                    } else {
                        countries.getDefaultGraph().find().forEachRemaining(composite.getDefaultGraph()::add);
                    }
                    for (boolean optimize : List.of(false, true)) {
                        String label = "http=" + http + " unionDefault=" + unionDefault + " optimize=" + optimize;
                        // Assert concrete bindings as well as parity with a materialized union.
                        assertEquals(List.of(expected), rows(reference, query, optimize), "reference " + label);
                        assertEquals(List.of(expected), rows(composite, query, optimize), label);
                    }
                } finally {
                    composite.close();
                    reference.close();
                    if (server != null) server.stop();
                    countries.close();
                    baci.close();
                }
            }
    }

    @Test
    void nestedLateralInsideRemoteGraphsKeepsPerInputLimitsAndMultiplicity() {
        List<String> queries = List.of(
                "SELECT ?g ?s ?item { VALUES ?c {<urn:countryValue> <urn:countryValue>} LATERAL { GRAPH ?g { {SELECT ?s ?c {?s <urn:p> ?x . ?x <urn:country> ?c} ORDER BY ?s LIMIT 2 OFFSET 1} LATERAL {BIND(?s AS ?item)} } } }",
                "SELECT ?g ?s ?item ?copy { VALUES ?g {<urn:a> <urn:b>} LATERAL { GRAPH ?g { {SELECT ?s {?s <urn:p> ?x} ORDER BY ?s LIMIT 1} LATERAL { {BIND(?s AS ?item)} UNION {BIND(?s AS ?copy)} } } } }",
                "SELECT ?count ?item { VALUES ?c {<urn:countryValue>} LATERAL { GRAPH <urn:b> { {SELECT ?c (COUNT(*) AS ?count) {?s <urn:p> ?x . ?x <urn:country> ?c} GROUP BY ?c} LATERAL {BIND(?count AS ?item)} } } }");
        for (boolean http : List.of(false, true))
            try (Fixture f = new Fixture(http)) {
                for (boolean optimize : List.of(false, true))
                    for (String query : queries) {
                        assertEquals(bag(rows(f.expected, query, optimize)), bag(rows(f.composite, query, optimize)),
                                "http=" + http + " optimize=" + optimize + " " + query);
                    }
            }
    }

    @Test
    void graphqlGeneratedNestedLateralSurvivesTwoSourceHttpFederation() throws Exception {
        for (boolean http : List.of(true, false)) {
            DatasetGraph countries = DatasetGraphFactory.create();
            DatasetGraph baci = DatasetGraphFactory.create();
            RDFDataMgr.read(countries, "lateral-country/part1.trig");
            Node graph = uri("service:baci");
            Node brazil = NodeFactory.createURI("https://data.projekt-provider.de/country/BRA");
            baci.add(graph, uri("observation"), NodeFactory.createURI("https://schema.projekt-provider.de/baci#importer"), uri("area"));
            baci.add(graph, uri("area"), NodeFactory.createURI("https://schema.projekt-provider.de/global#hasCountry"), brazil);
            DatasetGraph reference = DatasetGraphFactory.createGeneral();
            DatasetGraph composite = DatasetGraphFactory.createGeneral();
            FusekiServer server = http ? FusekiServer.create().port(0).add("/countries", countries).add("/baci", baci).build() : null;
            if (server != null) server.start();
            try {
                countries.getDefaultGraph().find().forEachRemaining(reference.getDefaultGraph()::add);
                baci.getGraph(graph).find().forEachRemaining(reference.getDefaultGraph()::add);
                reference.addGraph(uri("service:static"), countries.getDefaultGraph());
                reference.addGraph(graph, baci.getGraph(graph));
                composite.addGraph(uri("service:static"), countryDataProxy(countries, server, "/countries").getDefaultGraph());
                composite.addGraph(graph, countryDataProxy(baci, server, "/baci").getGraph(graph));
                composite.getContext().set(ProxyDatasetSettings.UNION_DEFAULT_GRAPH, true);
                for (boolean optimize : List.of(false, true))
                    for (String variant : List.of("graph-iri", "default", "graph-of", "reported")) {
                        String query;
                        try (var in = getClass().getResourceAsStream("/lateral-country/graphql-" + variant + ".rq")) {
                            assertNotNull(in);
                            query = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                        List<Binding> expected = rows(reference, query, optimize);
                        assertEquals(variant.equals("reported") ? 3 : 5, expected.size());
                        // The JSON assembler consumes state transitions in sequence.
                        assertEquals(expected, rows(composite, query, optimize),
                                "variant=" + variant + " http=" + http + " optimize=" + optimize);
                    }
            } finally {
                composite.close();
                reference.close();
                if (server != null) server.stop();
                countries.close();
                baci.close();
            }
        }
    }

    private static DatasetGraphOverRDFLink countryDataProxy(DatasetGraph backend, FusekiServer server, String endpoint) {
        return new DatasetGraphOverRDFLink(DsgSparqlExecutor.DEFAULT, false, false) {
            @Override
            public RDFLink newLink() {
                RDFLink delegate = server != null
                        ? RDFLinkHTTP.newBuilder().destination("http://localhost:" + server.getPort() + endpoint).build()
                        : RDFLink.connect(new DatasetGraphWrapper(backend) {
                            @Override
                            public void close() {}
                        });
                return (RDFLink) Proxy.newProxyInstance(RDFLink.class.getClassLoader(), new Class<?>[]{RDFLink.class},
                        (obj, method, args) -> {
                            if (method.getName().equals("query") && args[0] instanceof Query query) {
                                try {
                                    QueryFactory.create(query.toString(), Syntax.syntaxARQ);
                                } catch (RuntimeException e) {
                                    throw new AssertionError("Invalid outbound query:\n" + query, e);
                                }
                            }
                            try { return method.invoke(delegate, args); }
                            catch (InvocationTargetException e) { throw e.getCause(); }
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
        };
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
