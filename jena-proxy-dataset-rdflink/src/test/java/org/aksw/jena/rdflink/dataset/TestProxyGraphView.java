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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.aksw.jena.sparql.core.remote.DsgSparqlExecutor;
import org.apache.jena.atlas.lib.Creator;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.rdflink.RDFLink;
import org.apache.jena.rdflink.RDFLinkHTTP;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.DatasetGraphOne;
import org.apache.jena.sparql.core.DatasetGraphWrapper;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.util.Context;
import org.apache.jena.util.iterator.ExtendedIterator;
import org.junit.jupiter.api.Test;

class TestProxyGraphView {
    private static final Node GRAPH = NodeFactory.createURI("urn:remote:graph");
    private static final String PREFIX = "PREFIX : <urn:> ";

    private static DatasetGraph backend() {
        DatasetGraph result = DatasetGraphFactory.create();
        RDFParser.fromString("""
            PREFIX : <urn:>
            :default :p 99 .
            <urn:remote:graph> { :a :p 1 ; :q :b . :b :p 2 . }
            :other { :leak :p 999 . }
            """, Lang.TRIG).parse(result);
        return result;
    }

    private static RDFLink localLink(DatasetGraph backend) {
        return RDFLink.connect(new DatasetGraphWrapper(backend) {
            @Override public void close() { }
        });
    }

    /** Both graph and dataset access fail if evaluation falls back to find. */
    private static DatasetGraphOverRDFLink proxy(Creator<RDFLink> links, List<Query> queries) {
        return proxy(links, queries, false);
    }

    private static DatasetGraphOverRDFLink proxy(Creator<RDFLink> links, List<Query> queries, boolean allowFind) {
        return new DatasetGraphOverRDFLink(DsgSparqlExecutor.DEFAULT, false, false) {
            @Override public RDFLink newLink() {
                RDFLink delegate = links.create();
                return (RDFLink)Proxy.newProxyInstance(RDFLink.class.getClassLoader(),
                    new Class<?>[] { RDFLink.class }, (object, method, args) -> {
                        if (method.getName().equals("query") && args[0] instanceof Query query) {
                            queries.add(query.cloneQuery());
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
            }
            @Override public Iterator<Quad> find(Node g, Node s, Node p, Node o) {
                if (allowFind) return super.find(g, s, p, o);
                throw new AssertionError("DatasetGraph.find must not be called");
            }
            @Override public Iterator<Quad> findNG(Node g, Node s, Node p, Node o) {
                if (allowFind) return super.findNG(g, s, p, o);
                throw new AssertionError("DatasetGraph.findNG must not be called");
            }
            @Override public Graph getGraph(Node name) {
                return new GraphView(this, name) {
                    @Override protected ExtendedIterator<Triple> graphBaseFind(Triple pattern) {
                        if (allowFind) return super.graphBaseFind(pattern);
                        throw new AssertionError("Graph.find must not be called");
                    }
                };
            }
            @Override public Graph getDefaultGraph() {
                return getGraph(Quad.defaultGraphNodeGenerated);
            }
        };
    }

    private static List<Binding> rows(Graph graph, String sparql) {
        try (QueryExec exec = QueryExec.graph(graph).query(PREFIX + sparql).build()) {
            List<Binding> result = new ArrayList<>();
            exec.select().forEachRemaining(result::add);
            return result;
        }
    }

    private static void checkQueries(DatasetGraph backend, DatasetGraphOverRDFLink proxy, List<Query> sent) {
        List<String> queries = List.of(
            "SELECT * { ?s :p ?o } ORDER BY ?s",
            "SELECT ?s ?o { ?s :p ?o FILTER(?o > 1) } ORDER BY ?s LIMIT 1",
            "SELECT (COUNT(*) AS ?count) { ?s :p ?o }",
            "SELECT ?s { ?s :q/:p ?o }",
            "SELECT ?s ?x { ?s :p ?o OPTIONAL { ?s :q ?x } } ORDER BY ?s",
            "SELECT ?s { { SELECT ?s { ?s :p ?o } ORDER BY ?s LIMIT 1 } }",
            "SELECT ?s { ?s :p ?o FILTER NOT EXISTS { GRAPH :other { ?x ?p ?y } } } ORDER BY ?s",
            "SELECT ?s (EXISTS { GRAPH :other { ?x ?p ?y } } AS ?exists) { ?s :p ?o } ORDER BY ?s",
            "SELECT * { GRAPH ?g { ?s ?p ?o } }",
            "SELECT ?s { ?s :p ?o MINUS { GRAPH :other { ?s ?p ?x } } } ORDER BY ?s",
            "SELECT * { VALUES ?x { 1 } }");
        for (Node name : List.of(GRAPH, Quad.defaultGraphNodeGenerated, NodeFactory.createURI("urn:missing"))) {
            Graph actual = Quad.isDefaultGraph(name) ? proxy.getDefaultGraph() : proxy.getGraph(name);
            Graph expected = Quad.isDefaultGraph(name) ? backend.getDefaultGraph() : backend.getGraph(name);
            for (String query : queries) {
                int before = sent.size();
                assertEquals(rows(expected, query), rows(actual, query), name + " / " + query);
                assertEquals(before + 1, sent.size(), "one delegated query");
            }
        }
    }

    @Test void localDatasetLink() {
        DatasetGraph backend = backend();
        List<Query> sent = new ArrayList<>();
        checkQueries(backend, proxy(() -> localLink(backend), sent), sent);
    }

    @Test void httpLink() {
        DatasetGraph backend = backend();
        FusekiServer server = FusekiServer.create().port(0).add("/data", backend).build();
        server.start();
        try {
            List<Query> sent = new ArrayList<>();
            checkQueries(backend, proxy(() -> RDFLinkHTTP.newBuilder()
                .destination("http://localhost:" + server.getPort() + "/data").build(), sent), sent);
        } finally {
            server.stop();
        }
    }

    @Test void askConstructAndAlgebra() {
        DatasetGraph backend = backend();
        List<Query> sent = new ArrayList<>();
        Graph graph = proxy(() -> localLink(backend), sent).getGraph(GRAPH);
        try (QueryExec exec = QueryExec.graph(graph).query(PREFIX + "ASK { :a :p 1 }").build()) {
            assertTrue(exec.ask());
        }
        try (QueryExec exec = QueryExec.graph(graph).query(PREFIX + "CONSTRUCT { ?s :copy ?o } { ?s :p ?o }").build()) {
            assertEquals(2, exec.construct().size());
        }
        var factory = new QueryEngineFactoryDatasetGraphOverRDFLink();
        var op = Algebra.compile(QueryFactory.create(PREFIX + "SELECT * { ?s :p ?o }"));
        DatasetGraph one = DatasetGraphOne.create(graph);
        assertTrue(factory.accept(op, one, Context.emptyContext()));
        var plan = factory.create(op, one, BindingFactory.empty(), Context.emptyContext());
        try {
            var iterator = plan.iterator();
            List<Binding> bindings = new ArrayList<>();
            iterator.forEachRemaining(bindings::add);
            assertEquals(2, bindings.size());
        } finally {
            plan.close();
        }
    }

    @Test void syntaxAndServiceScopeArePreserved() {
        Query original = QueryFactory.create(PREFIX + """
            SELECT DISTINCT ?s WHERE {
              ?s :p ?o
              SERVICE <urn:endpoint> { GRAPH :other { ?s :q ?x } }
            } ORDER BY ?s LIMIT 5 OFFSET 1
            """);
        String before = original.toString();
        Query scoped = QueryGraphScope.rewrite(original, GRAPH, BindingFactory.empty());
        assertEquals(before, original.toString());
        assertEquals(List.of(GRAPH.getURI()), scoped.getGraphURIs());
        assertTrue(scoped.isDistinct());
        assertEquals(5, scoped.getLimit());
        assertEquals(1, scoped.getOffset());
        assertEquals(original.getOrderBy(), scoped.getOrderBy());
        assertEquals(original.getQueryPattern(), scoped.getQueryPattern());
    }

    @Test void compositeAliasesAndVariableGraphs() {
        DatasetGraph backend = backend();
        List<Query> sent = new ArrayList<>();
        DatasetGraphOverRDFLink proxy = proxy(() -> localLink(backend), sent);
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        composite.addGraph(NodeFactory.createURI("urn:first"), proxy.getGraph(GRAPH));
        composite.addGraph(NodeFactory.createURI("urn:second"), proxy.getDefaultGraph());
        DatasetGraph expected = DatasetGraphFactory.createGeneral();
        expected.addGraph(NodeFactory.createURI("urn:first"), backend.getGraph(GRAPH));
        expected.addGraph(NodeFactory.createURI("urn:second"), backend.getDefaultGraph());
        composite.addGraph(NodeFactory.createURI("urn:local"), backend.getDefaultGraph());
        expected.addGraph(NodeFactory.createURI("urn:local"), backend.getDefaultGraph());
        for (String query : List.of(
                "SELECT * { GRAPH :first { ?s :p ?o FILTER(?o > 1) } }",
                "SELECT * { GRAPH :local { GRAPH :first { ?s :p ?o } } } ORDER BY ?s",
                "SELECT * { GRAPH ?service { ?s :p ?o } } ORDER BY ?service ?s",
                "SELECT * { VALUES ?service { :first } GRAPH ?service { ?s :p ?o } } ORDER BY ?s",
                "SELECT ?service (COUNT(*) AS ?count) { GRAPH ?service { ?s :p ?o } } GROUP BY ?service ORDER BY ?service",
                "SELECT * { GRAPH :first { ?s :p ?o } GRAPH :second { ?x :p ?n } } ORDER BY ?s",
                "SELECT ?service { GRAPH ?service { ?s :p ?o FILTER(?service = :first) } } ORDER BY ?service")) {
            try (QueryExec e = QueryExec.dataset(expected).query(PREFIX + query).build();
                 QueryExec a = QueryExec.dataset(composite).query(PREFIX + query).build()) {
                List<Binding> erows = new ArrayList<>();
                List<Binding> arows = new ArrayList<>();
                e.select().forEachRemaining(erows::add);
                a.select().forEachRemaining(arows::add);
                assertEquals(erows, arows, query);
            }
        }
        assertTrue(sent.stream().allMatch(q -> q.getGraphURIs().isEmpty()
            || q.getGraphURIs().equals(List.of(GRAPH.getURI()))));
    }

    @Test void nestedGraphsUseTheCompositeDataset() {
        DatasetGraph first = backend();
        DatasetGraph second = DatasetGraphFactory.create();
        RDFParser.fromString("PREFIX : <urn:> :b :label 'second service' .", Lang.TURTLE).parse(second);
        List<Query> firstQueries = new ArrayList<>();
        List<Query> secondQueries = new ArrayList<>();
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        DatasetGraph expected = DatasetGraphFactory.createGeneral();
        Node foo = NodeFactory.createURI("urn:foo");
        Node bar = NodeFactory.createURI("urn:bar");
        composite.addGraph(foo, proxy(() -> localLink(first), firstQueries, true).getGraph(GRAPH));
        composite.addGraph(bar, proxy(() -> localLink(second), secondQueries, true).getDefaultGraph());
        expected.addGraph(foo, first.getGraph(GRAPH));
        expected.addGraph(bar, second.getDefaultGraph());
        for (String query : List.of(
                "SELECT * { GRAPH :foo { ?s :q ?x GRAPH :bar { ?x :label ?label } } }",
                "SELECT * { GRAPH :foo { ?s :q ?x OPTIONAL { GRAPH :bar { ?x :label ?label } } } }",
                "SELECT * { GRAPH :foo { ?s :q ?x FILTER EXISTS { GRAPH :bar { ?x :label ?label } } } }",
                "SELECT * { GRAPH :foo { ?s :p ?n FILTER NOT EXISTS { GRAPH :bar { ?s :label ?label } } } } ORDER BY ?s",
                "SELECT * { GRAPH :foo { { SELECT ?x ?label { GRAPH :bar { ?x :label ?label } } } ?s :q ?x } }",
                "SELECT * { VALUES ?outer { :foo } GRAPH ?outer { ?s :q ?x GRAPH ?inner { ?x :label ?label } } }",
                "SELECT * { GRAPH :foo { GRAPH :bar { GRAPH :foo { ?s :q ?x } ?x :label ?label } } }",
                "SELECT ?s ?exists { GRAPH :foo { ?s :q ?x BIND(EXISTS { GRAPH :bar { ?x :label ?label } } AS ?exists) } }")) {
            try (QueryExec e = QueryExec.dataset(expected).query(PREFIX + query).build();
                 QueryExec a = QueryExec.dataset(composite).query(PREFIX + query).build()) {
                List<Binding> erows = new ArrayList<>();
                List<Binding> arows = new ArrayList<>();
                e.select().forEachRemaining(erows::add);
                a.select().forEachRemaining(arows::add);
                assertFalse(erows.isEmpty(), "Test must exercise a successful nested match");
                assertEquals(erows, arows, query);
            }
        }
        assertFalse(firstQueries.isEmpty());
        assertFalse(secondQueries.isEmpty());
        for (Query query : firstQueries) {
            assertFalse(query.toString().contains("urn:bar"), "Local aliases must not leak to remote services");
        }
        for (Query query : secondQueries) {
            assertFalse(query.toString().contains("urn:foo"), "Local aliases must not leak to remote services");
        }
    }

    @Test void valuesSelectsServicesBeforeDispatch() {
        DatasetGraph backend = backend();
        List<Query> first = new ArrayList<>();
        List<Query> second = new ArrayList<>();
        List<Query> excluded = new ArrayList<>();
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        composite.addGraph(NodeFactory.createURI("urn:foo"), proxy(() -> localLink(backend), first).getGraph(GRAPH));
        composite.addGraph(NodeFactory.createURI("urn:bar"), proxy(() -> localLink(backend), second).getDefaultGraph());
        composite.addGraph(NodeFactory.createURI("urn:excluded"), proxy(() -> localLink(backend), excluded).getGraph(GRAPH));
        try (QueryExec exec = QueryExec.dataset(composite).query(PREFIX + """
                SELECT ?service ?s ?o {
                    VALUES ?service { :foo :bar }
                    GRAPH ?service { ?s :p ?o }
                } ORDER BY ?service ?s
                """).build()) {
            List<Binding> rows = new ArrayList<>();
            exec.select().forEachRemaining(rows::add);
            assertEquals(3, rows.size());
        }
        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertTrue(excluded.isEmpty(), "VALUES must prevent dispatch to unselected services");
        for (Query query : List.of(first.get(0), second.get(0))) {
            assertFalse(query.toString().contains("VALUES"), "Service-selection VALUES must stay local");
            assertFalse(query.toString().contains("urn:foo"));
            assertFalse(query.toString().contains("urn:bar"));
        }
    }

    @Test void assembledHttpGraphViews() {
        DatasetGraph backend = backend();
        FusekiServer server = FusekiServer.create().port(0).add("/data", backend).build();
        server.start();
        try {
            String description = """
                PREFIX ja: <http://jena.hpl.hp.com/2005/11/Assembler#>
                PREFIX dsg: <https://w3id.org/aksw/jena/dataset#>
                <urn:root> a ja:RDFDataset ;
                    ja:namedGraph [ ja:graphName <urn:foo> ; ja:graph <urn:view> ] ;
                    ja:namedGraph [ ja:graphName <urn:bar> ; ja:graph <urn:defaultView> ] .
                <urn:view> a ja:ViewGraph ; ja:dataset <urn:remote> ; ja:graphName <urn:remote:graph> .
                <urn:defaultView> a ja:ViewGraph ; ja:dataset <urn:remote> .
                <urn:remote> a dsg:DatasetHTTP ; dsg:destination "%s" .
                """.formatted("http://localhost:" + server.getPort() + "/data");
            var spec = RDFParser.fromString(description, Lang.TURTLE).toModel();
            var dataset = org.apache.jena.query.DatasetFactory.assemble(spec.getResource("urn:root"));
            assertEquals(2, rows(dataset.asDatasetGraph().getGraph(NodeFactory.createURI("urn:foo")),
                "SELECT * { ?s :p ?o }").size());
            try (QueryExec exec = QueryExec.dataset(dataset.asDatasetGraph()).query(PREFIX + """
                    SELECT ?service ?s ?o {
                        VALUES ?service { :foo :bar }
                        GRAPH ?service { ?s :p ?o }
                    }
                    """).build()) {
                List<Binding> rows = new ArrayList<>();
                exec.select().forEachRemaining(rows::add);
                assertEquals(3, rows.size());
            }
        } finally {
            server.stop();
        }
    }

    @Test void initialBindingIsAppliedToTheViewQuery() {
        DatasetGraph backend = backend();
        List<Query> sent = new ArrayList<>();
        Graph graph = proxy(() -> localLink(backend), sent).getGraph(GRAPH);
        var factory = new QueryEngineFactoryDatasetGraphOverRDFLink();
        var plan = factory.create(QueryFactory.create(PREFIX + "SELECT ?s ?o { ?s :p ?o }"),
            DatasetGraphOne.create(graph),
            BindingFactory.binding(Var.alloc("s"), NodeFactory.createURI("urn:a")), Context.emptyContext());
        try {
            List<Binding> result = new ArrayList<>();
            plan.iterator().forEachRemaining(result::add);
            assertEquals(1, result.size());
            assertEquals(NodeFactory.createURI("urn:a"), result.get(0).get(Var.alloc("s")));
        } finally {
            plan.close();
        }
        assertEquals(1, sent.size());
    }

    @Test void unsupportedQueriesAreRejectedRatherThanFallingBack() {
        DatasetGraph backend = backend();
        Graph graph = proxy(() -> localLink(backend), new ArrayList<>()).getGraph(GRAPH);
        for (String query : List.of("SELECT * FROM <urn:other> { ?s ?p ?o }", "DESCRIBE <urn:a>")) {
            assertThrows(UnsupportedOperationException.class, () -> {
                try (QueryExec exec = QueryExec.graph(graph).query(query).build()) {
                    if (query.startsWith("DESCRIBE")) exec.describe();
                    else exec.select().hasNext();
                }
            });
        }
    }
    private static List<Binding> datasetRows(DatasetGraph dataset, String sparql) {
        try (QueryExec exec = QueryExec.dataset(dataset).query(PREFIX + sparql).build()) {
            List<Binding> result = new ArrayList<>();
            exec.select().forEachRemaining(result::add);
            return result;
        }
    }

    @Test void aggregateQueriesDelegateCountsByDefault() {
        DatasetGraph backend = backend();
        DatasetGraph empty = DatasetGraphFactory.create();
        List<Query> sent = new ArrayList<>();
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        DatasetGraph expected = DatasetGraphFactory.createGeneral();
        for (String name : List.of("first", "second", "empty")) {
            Node alias = NodeFactory.createURI("urn:" + name);
            DatasetGraph source = name.equals("empty") ? empty : backend;
            Node graph = name.equals("first") ? GRAPH : Quad.defaultGraphNodeGenerated;
            composite.addGraph(alias, proxy(() -> localLink(source), sent).getGraph(graph));
            expected.addGraph(alias, Quad.isDefaultGraph(graph) ? source.getDefaultGraph() : source.getGraph(graph));
        }
        for (String query : List.of(
                "SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } }",
                "SELECT ?g (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } } GROUP BY ?g ORDER BY ?g",
                "SELECT ?o (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } } GROUP BY ?o ORDER BY ?o",
                "SELECT (COUNT(?missing) AS ?c) { GRAPH ?g { ?s :p ?o } }",
                "SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s :missing ?o } }",
                "SELECT ?g (COUNT(*) AS ?c) { GRAPH ?g { ?s :missing ?o } } GROUP BY ?g",
                "SELECT ?g (COUNT(?missing) AS ?c) { GRAPH ?g { ?s :p ?o } } GROUP BY ?g ORDER BY ?g",
                "SELECT ?g (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } } GROUP BY ?g HAVING(COUNT(*) > 1) ORDER BY ?g")) {
            sent.clear();
            assertEquals(datasetRows(expected, query), datasetRows(composite, query), query);
            assertEquals(3, sent.size(), query);
            assertTrue(sent.stream().allMatch(Query::hasAggregators), "Every endpoint must receive a group: " + sent);
        }
    }

    @Test void serviceSelectionAndMixedLocalGraphsRemainCorrectForAggregates() {
        DatasetGraph backend = backend();
        List<Query> sent = new ArrayList<>();
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        DatasetGraph expected = DatasetGraphFactory.createGeneral();
        Node first = NodeFactory.createURI("urn:first");
        Node local = NodeFactory.createURI("urn:local");
        composite.addGraph(first, proxy(() -> localLink(backend), sent).getGraph(GRAPH));
        expected.addGraph(first, backend.getGraph(GRAPH));
        composite.addGraph(local, backend.getDefaultGraph());
        expected.addGraph(local, backend.getDefaultGraph());
        for (String query : List.of(
                "SELECT ?g (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } } GROUP BY ?g ORDER BY ?g",
                "SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } }",
                "SELECT ?g (COUNT(DISTINCT ?s) AS ?c) { GRAPH ?g { ?s :p ?o } } GROUP BY ?g ORDER BY ?g",
                "SELECT (SUM(?o) AS ?c) { GRAPH ?g { ?s :p ?o } }",
                "SELECT (COUNT(*) AS ?c) { VALUES ?g { :first :first } GRAPH ?g { ?s :p ?o } }",
                "SELECT (COUNT(*) AS ?c) { VALUES ?g { :local } GRAPH ?g { ?s :p ?o } }")) {
            sent.clear();
            assertEquals(datasetRows(expected, query), datasetRows(composite, query), query);
            if (query.contains("VALUES ?g { :local }")) {
                assertTrue(sent.isEmpty(), "Unselected proxy graphs must not be queried");
            }
        }
    }

    @Test void optimizerIsDecoratedOnlyForTheExecutionRequest() {
        DatasetGraph backend = backend();
        List<Query> sent = new ArrayList<>();
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        composite.addGraph(NodeFactory.createURI("urn:first"), proxy(() -> localLink(backend), sent).getGraph(GRAPH));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        org.apache.jena.sparql.algebra.optimize.RewriteFactory factory = context -> op -> {
            calls.incrementAndGet();
            return org.apache.jena.sparql.algebra.optimize.Optimize.stdOptimizationFactory.create(context).rewrite(op);
        };
        composite.getContext().set(org.apache.jena.sparql.ARQConstants.sysOptimizerFactory, factory);
        datasetRows(composite, "SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } }");
        assertEquals(1, calls.get());
        assertSame(factory, composite.getContext().get(org.apache.jena.sparql.ARQConstants.sysOptimizerFactory));
        assertTrue(sent.get(0).hasAggregators());
    }

    @Test void proxyEngineCanBeDisabledByDatasetOrRequestContext() {
        DatasetGraph backend = backend();
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        composite.addGraph(NodeFactory.createURI("urn:first"), proxy(() -> localLink(backend), new ArrayList<>(), true).getGraph(GRAPH));
        Query query = QueryFactory.create(PREFIX + "SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } }");
        var op = Algebra.compile(query);
        var factory = new QueryEngineFactoryProxyGraphs();
        assertTrue(factory.accept(query, composite, Context.emptyContext()));
        assertTrue(factory.accept(op, composite, Context.emptyContext()));
        composite.getContext().set(QueryEngineFactoryProxyGraphs.ENABLED, false);
        assertFalse(factory.accept(query, composite, Context.emptyContext()));
        assertFalse(factory.accept(op, composite, Context.emptyContext()));
        assertEquals("2", datasetRows(composite, query.toString()).get(0).get(Var.alloc("c")).getLiteralLexicalForm());
        Context requestContext = Context.create();
        requestContext.set(QueryEngineFactoryProxyGraphs.ENABLED, true);
        assertTrue(factory.accept(query, composite, requestContext));
        requestContext.set(QueryEngineFactoryProxyGraphs.ENABLED, "false");
        assertFalse(factory.accept(query, composite, requestContext));
        assertFalse(factory.accept(op, composite, requestContext));
        assertFalse(factory.accept(query, null, Context.emptyContext()));
    }

    @Test void completeGroupsAndSubqueryScopesArePreserved() {
        DatasetGraph backend = backend();
        List<Query> sent = new ArrayList<>();
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        DatasetGraph expected = DatasetGraphFactory.createGeneral();
        Node alias = NodeFactory.createURI("urn:first");
        composite.addGraph(alias, proxy(() -> localLink(backend), sent).getGraph(GRAPH));
        expected.addGraph(alias, backend.getGraph(GRAPH));
        for (String query : List.of(
                "SELECT (COUNT(DISTINCT ?s) AS ?n) (SUM(?o) AS ?sum) (AVG(?o) AS ?avg) { GRAPH :first { ?s :p ?o } }",
                "SELECT ?s ?n { VALUES ?s { :a } { SELECT (COUNT(*) AS ?n) { GRAPH :first { ?s :p ?o } } } }",
                "SELECT ?s ?n { VALUES ?s { :a } { SELECT ?s (COUNT(*) AS ?n) { GRAPH ?g { ?s :p ?o } } GROUP BY ?s } }",
                "SELECT (COUNT(*) AS ?c) { { SELECT ?g ?s { GRAPH ?g { ?s :p ?o } } } }",
                "SELECT (COUNT(RAND()) AS ?c) { GRAPH :first { ?s :p ?o } }")) {
            sent.clear();
            assertEquals(datasetRows(expected, query), datasetRows(composite, query), query);
            if (query.contains("COUNT(DISTINCT")) assertTrue(sent.get(0).hasAggregators());
            if (query.contains("RAND()")) assertFalse(sent.get(0).hasAggregators());
        }
    }

    @Test void assembledGraphViewsDelegateAggregatesWithoutConfiguration() {
        DatasetGraph backend = backend();
        FusekiServer server = FusekiServer.create().port(0).add("/data", backend).build();
        server.start();
        try {
            String description = """
                PREFIX ja: <http://jena.hpl.hp.com/2005/11/Assembler#>
                PREFIX dsg: <https://w3id.org/aksw/jena/dataset#>
                <urn:root> a ja:RDFDataset ;
                    ja:namedGraph [ ja:graphName <urn:first> ; ja:graph <urn:view> ] .
                <urn:view> a ja:ViewGraph ; ja:dataset <urn:remote> ; ja:graphName <urn:remote:graph> .
                <urn:remote> a dsg:DatasetHTTP ; dsg:destination "%s" .
                """.formatted("http://localhost:" + server.getPort() + "/data");
            var spec = RDFParser.fromString(description, Lang.TURTLE).toModel();
            var enabledDataset = org.apache.jena.query.DatasetFactory.assemble(spec.getResource("urn:root"));
            try {
                assertEquals("2", datasetRows(enabledDataset.asDatasetGraph(),
                    "SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } }")
                    .get(0).get(Var.alloc("c")).getLiteralLexicalForm());
                var grouped = datasetRows(enabledDataset.asDatasetGraph(),
                    "SELECT ?g (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } } GROUP BY ?g");
                assertEquals(1, grouped.size());
                assertEquals(NodeFactory.createURI("urn:first"), grouped.get(0).get(Var.alloc("g")));
                assertEquals("2", grouped.get(0).get(Var.alloc("c")).getLiteralLexicalForm());
            } finally { enabledDataset.close(); }
            spec.getResource("urn:root").addProperty(org.apache.jena.assembler.JA.context,
                spec.createResource()
                    .addProperty(org.apache.jena.assembler.JA.cxtName,
                        spec.createResource(QueryEngineFactoryProxyGraphs.ENABLED.getSymbol()))
                    .addLiteral(org.apache.jena.assembler.JA.cxtValue, false));
            var disabledDataset = org.apache.jena.query.DatasetFactory.assemble(spec.getResource("urn:root"));
            try {
                var query = QueryFactory.create("SELECT * WHERE { GRAPH ?g { ?s ?p ?o } }");
                assertFalse(new QueryEngineFactoryProxyGraphs().accept(query,
                    disabledDataset.asDatasetGraph(), disabledDataset.getContext()));
            } finally { disabledDataset.close(); }
        } finally {
            server.stop();
        }
    }

    @Test void nonEmptyInitialBindingsRetainTheExistingEvaluationPath() {
        DatasetGraph backend = backend();
        List<Query> sent = new ArrayList<>();
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        composite.addGraph(NodeFactory.createURI("urn:first"), proxy(() -> localLink(backend), sent).getGraph(GRAPH));
        var factory = new QueryEngineFactoryProxyGraphs();
        Query query = QueryFactory.create(PREFIX + "SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } }");
        var plan = factory.create(query, composite,
            BindingFactory.binding(Var.alloc("s"), NodeFactory.createURI("urn:a")), Context.emptyContext());
        try {
            List<Binding> actual = new ArrayList<>();
            plan.iterator().forEachRemaining(actual::add);
            assertEquals(1, actual.size());
            assertEquals("1", actual.get(0).get(Var.alloc("c")).getLiteralLexicalForm());
            assertEquals(1, sent.size());
            assertFalse(sent.get(0).hasAggregators());
        } finally { plan.close(); }
    }


    @Test void distinctIsDelegatedPerGraphAndRetainedAcrossGraphs() {
        DatasetGraph backend = DatasetGraphFactory.create();
        RDFParser.fromString("PREFIX : <urn:> :a :p 1 . :b :p 1 . :c :p 2 .", Lang.TURTLE).parse(backend);
        List<Query> sent = new ArrayList<>();
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        DatasetGraph expected = DatasetGraphFactory.createGeneral();
        for (String name : List.of("first", "second")) {
            Node alias = NodeFactory.createURI("urn:" + name);
            composite.addGraph(alias, proxy(() -> localLink(backend), sent).getDefaultGraph());
            expected.addGraph(alias, backend.getDefaultGraph());
        }
        for (String query : List.of(
                "SELECT DISTINCT ?o { GRAPH ?g { ?s :p ?o } }",
                "SELECT DISTINCT ?g ?o { GRAPH ?g { ?s :p ?o } }",
                "SELECT DISTINCT ?o { GRAPH ?g { ?s :p ?o } FILTER(?o > 0) }")) {
            sent.clear();
            var actual = datasetRows(composite, query);
            assertEquals(new java.util.HashSet<>(datasetRows(expected, query)), new java.util.HashSet<>(actual), query);
            assertEquals(query.contains("?g ?o") ? 4 : 2, actual.size(), "Cross-graph duplicate elimination");
            assertEquals(2, sent.size());
            assertTrue(sent.stream().allMatch(Query::isDistinct), "Expected remote DISTINCT: " + sent);
            assertTrue(sent.stream().allMatch(q -> q.getResultVars().size() == 1), "Only the distinct key is transferred");
        }
        sent.clear();
        var selected = datasetRows(composite, "SELECT DISTINCT ?o { VALUES ?g { :first } GRAPH ?g { ?s :p ?o } }");
        assertEquals(2, selected.size());
        assertEquals(1, sent.size(), "Unselected services receive no request");
        sent.clear();
        assertEquals("6", datasetRows(composite, "SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s :p ?o } }")
            .get(0).get(Var.alloc("c")).getLiteralLexicalForm());
        assertTrue(sent.stream().noneMatch(Query::isDistinct), "COUNT retains duplicate input rows");
    }

    @Test void httpDistinctPushdownWorksWithoutAnOptIn() {
        DatasetGraph backend = DatasetGraphFactory.create();
        RDFParser.fromString("PREFIX : <urn:> :a :p 1 . :b :p 1 . :c :p 2 .", Lang.TURTLE).parse(backend);
        FusekiServer server = FusekiServer.create().port(0).add("/data", backend).build();
        server.start();
        try {
            List<Query> sent = new ArrayList<>();
            DatasetGraph composite = DatasetGraphFactory.createGeneral();
            for (String name : List.of("first", "second")) {
                composite.addGraph(NodeFactory.createURI("urn:" + name), proxy(() -> RDFLinkHTTP.newBuilder()
                    .destination("http://localhost:" + server.getPort() + "/data").build(), sent).getDefaultGraph());
            }
            assertEquals(2, datasetRows(composite, "SELECT DISTINCT ?o { GRAPH ?g { ?s :p ?o } }").size());
            assertEquals(2, sent.size());
            assertTrue(sent.stream().allMatch(Query::isDistinct));
            assertTrue(sent.stream().allMatch(q -> q.getResultVars().size() == 1));
        } finally { server.stop(); }
    }

    private static void checkOrderedFederation(Creator<RDFLink> links, DatasetGraph backend) {
        List<Query> sent = new ArrayList<>();
        DatasetGraph remote = proxy(links, sent);
        DatasetGraph composite = DatasetGraphFactory.createGeneral();
        DatasetGraph reference = DatasetGraphFactory.createGeneral();
        for (String name : List.of("urn:alias:a", "urn:alias:b")) {
            Node alias = NodeFactory.createURI(name);
            composite.addGraph(alias, remote.getGraph(GRAPH));
            reference.addGraph(alias, backend.getGraph(GRAPH));
        }
        List<String> queries = List.of(
                "SELECT ?g ?o { GRAPH ?g { ?s :p ?o } } ORDER BY ?o ?g",
                "SELECT ?g ?o { GRAPH ?g { ?s :p ?o } } ORDER BY DESC(?o) ?g LIMIT 2 OFFSET 1",
                "SELECT ?g ?o { GRAPH ?g { ?s :p ?o } } ORDER BY DESC(?g) ?o",
                "SELECT ?o { GRAPH <urn:alias:a> { ?s :p ?o } } ORDER BY DESC(?o)",
                "SELECT ?o { GRAPH ?g { ?s :p ?o } FILTER(?o > 1) } ORDER BY DESC(?o)",
                "SELECT ?g ?s { GRAPH ?g { ?s :p ?o } } ORDER BY DESC(?o) ?g",
                "SELECT ?o { { SELECT ?g ?o { GRAPH ?g { ?s :p ?o } } ORDER BY DESC(?o) ?g LIMIT 2 } } ORDER BY ?o",
                "SELECT ?o { GRAPH ?g { ?s :p ?o } } ORDER BY ?o LIMIT 0");
        for (String query : queries) {
            sent.clear();
            assertEquals(datasetRows(reference, query), datasetRows(composite, query), query);
            if (!query.endsWith("LIMIT 0")) {
                assertFalse(sent.isEmpty(), query);
                assertTrue(sent.stream().allMatch(Query::hasOrderBy), query + " / " + sent);
                assertTrue(sent.stream().noneMatch(Query::hasLimit), "limits must remain global");
            }
        }
        // DISTINCT may be moved below ORDER by ARQ. It is an intentional placement barrier.
        for (String fallback : List.of(
                "SELECT DISTINCT ?o { GRAPH ?g { ?s :p ?o } } ORDER BY ?o",
                "SELECT DISTINCT ?o { GRAPH ?g { ?s :p ?o } } ORDER BY DESC(?o) LIMIT 1",
                "SELECT ?g ?o { GRAPH ?g { ?s :p ?o } } ORDER BY (?o + 1) ?g")) {
            assertEquals(datasetRows(reference, fallback), datasetRows(composite, fallback), fallback);
        }
        // Local branches must participate in the same ordered merge.
        Node local = NodeFactory.createURI("urn:local");
        composite.addGraph(local, backend.getDefaultGraph());
        reference.addGraph(local, backend.getDefaultGraph());
        String mixed = "SELECT ?g ?o { GRAPH ?g { ?s :p ?o } } ORDER BY DESC(?o) ?g";
        assertEquals(datasetRows(reference, mixed), datasetRows(composite, mixed));
    }

    @Test void orderIsDelegatedAndMergedAcrossProxyGraphs() {
        DatasetGraph backend = backend();
        checkOrderedFederation(() -> localLink(backend), backend);
    }

    @Test void orderedFederationOverHttp() {
        DatasetGraph backend = backend();
        FusekiServer server = FusekiServer.create().port(0).add("/data", backend).build();
        server.start();
        try {
            checkOrderedFederation(() -> RDFLinkHTTP.newBuilder()
                    .destination("http://localhost:" + server.getPort() + "/data").build(), backend);
        } finally { server.stop(); }
    }
}
