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
import java.util.*;
import org.aksw.jena.proxy.algebra.udg.TransformUnionDefaultGraph;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.graph.*;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdflink.RDFLinkHTTP;
import org.apache.jena.riot.*;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.binding.*;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.util.*;
import org.junit.jupiter.api.Test;

/** Assembler/engine integration only; standalone UDG semantics have their own test class. */
class TestDatasetAssemblerProxy {
    private static final String PREFIX = "PREFIX : <urn:> ";
    private static Model specification(String options) {
        return RDFParser.fromString("""
            PREFIX ja: <http://jena.hpl.hp.com/2005/11/Assembler#>
            PREFIX dsg: <https://w3id.org/aksw/jena/dataset#>
            <urn:dataset> a dsg:DatasetProxy ;
                ja:namedGraph [ ja:graphName <urn:g1> ; ja:graph <urn:model1> ] ;
                ja:namedGraph [ ja:graphName <urn:g2> ; ja:graph <urn:model2> ] %s .
            <urn:model1> a ja:MemoryModel .
            <urn:model2> a ja:MemoryModel .
            """.formatted(options), Lang.TURTLE).toModel();
    }

    private static DatasetGraph assemble(String options) {
        Model spec = specification(options);
        return DatasetFactory.assemble(spec.getResource("urn:dataset")).asDatasetGraph();
    }

    private static void populate(DatasetGraph dataset) {
        RDFParser.fromString("""
            PREFIX : <urn:>
            :physical :p 99 .
            :g1 { :s :p :m . :dup :p :o . }
            :g2 { :m :q :end . :dup :p :o . }
            """, Lang.TRIG).parse(dataset);
    }

    private static List<Binding> rows(DatasetGraph dataset, String query, Context request) {
        var builder = QueryExec.dataset(dataset).query(PREFIX + query);
        if (request != null) builder.context(request);
        try (var exec = builder.build()) {
            List<Binding> result = new ArrayList<>();
            exec.select().forEachRemaining(b -> result.add(BindingFactory.copy(b)));
            return result;
        }
    }

    private static long count(DatasetGraph dataset, Context request) {
        return Long.parseLong(rows(dataset, "SELECT (COUNT(*) AS ?c) { ?s ?p ?o }", request)
                .get(0).get(Var.alloc("c")).getLiteralLexicalForm());
    }

    @Test void assemblerCreatesPlainMapLinkAndOnlySetsSpecifiedContextOptions() {
        DatasetGraph plain = assemble("");
        assertEquals(DatasetGraphMapLink.class, plain.getClass());
        assertFalse(plain.getContext().isDefined(ProxyDatasetSettings.UNION_DEFAULT_GRAPH));
        assertFalse(plain.getContext().isDefined(TransformUnionDefaultGraph.STRICT));
        populate(plain);
        assertEquals(1, count(plain, null));
        assertFalse(new QueryEngineFactoryProxyGraphs().accept(QueryFactory.create("SELECT * {}"), plain, null));
        plain.close();
        DatasetGraph configured = assemble("; dsg:unionDefaultGraph true ; dsg:udgStrict false ; dsg:udgDeduplicate false");
        assertEquals(DatasetGraphMapLink.class, configured.getClass());
        assertTrue(configured.getContext().isTrue(ProxyDatasetSettings.UNION_DEFAULT_GRAPH));
        assertTrue(configured.getContext().isFalse(TransformUnionDefaultGraph.STRICT));
        assertTrue(configured.getContext().isFalse(TransformUnionDefaultGraph.DEDUPLICATE));
        configured.close();
    }

    @Test void localUnionQueriesAndProvenanceWorkWithoutProxyViews() {
        DatasetGraph dataset = assemble("; dsg:unionDefaultGraph true");
        populate(dataset);
        assertEquals(3, count(dataset, null));
        assertEquals(1, rows(dataset, "SELECT ?end { :s :p ?m . ?m :q ?end }", null).size());
        var provenance = rows(dataset, "SELECT DISTINCT ?g { BIND(:s AS ?s) LATERAL { GRAPH ?g { ?s ?p ?o } } }", null);
        assertEquals(List.of(BindingFactory.binding(Var.alloc("g"), NodeFactory.createURI("urn:g1"))), provenance);
        var zeroColumns = rows(dataset, "SELECT * { :dup :p :o }", null);
        assertEquals(1, zeroColumns.size());
        assertTrue(zeroColumns.get(0).isEmpty());
        assertEquals(2, rows(dataset, "SELECT * { ?s :p _:object }", null).size());
        try (var ask = QueryExec.dataset(dataset).query(PREFIX + "ASK { :s :p :m }").build()) {
            assertTrue(ask.ask());
        }
        try (var construct = QueryExec.dataset(dataset).query(PREFIX + "CONSTRUCT { ?s :p ?o } WHERE { ?s :p ?o }").build()) {
            Graph graph = construct.construct();
            try { assertEquals(2, graph.size()); } finally { graph.close(); }
        }
        dataset.close();
    }

    @Test void contextOptionsWorkWithoutTheCustomAssemblerAndRequestValuesWin() {
        DatasetGraph dataset = DatasetGraphFactory.createGeneral();
        populate(dataset);
        dataset.getContext().set(ProxyDatasetSettings.UNION_DEFAULT_GRAPH, true);
        dataset.getContext().set(TransformUnionDefaultGraph.DEDUPLICATE, false);
        assertEquals(4, count(dataset, null));
        Context request = Context.create();
        request.set(TransformUnionDefaultGraph.DEDUPLICATE, true);
        assertEquals(3, count(dataset, request));
        assertTrue(dataset.getContext().isFalse(TransformUnionDefaultGraph.DEDUPLICATE));
        request.set(ProxyDatasetSettings.UNION_DEFAULT_GRAPH, false);
        assertEquals(1, count(dataset, request));
        dataset.getContext().set(ProxyDatasetSettings.UNION_DEFAULT_GRAPH, false);
        request.set(ProxyDatasetSettings.UNION_DEFAULT_GRAPH, true);
        assertEquals(3, count(dataset, request));
        assertTrue(dataset.getContext().isFalse(ProxyDatasetSettings.UNION_DEFAULT_GRAPH));
        assertFalse(request.isDefined(org.apache.jena.sparql.ARQConstants.sysOptimizerFactory));
        dataset.close();
    }

    @Test void strictnessAndOptimizerDisableAreIndependentOfUnionSemantics() {
        DatasetGraph dataset = assemble("; dsg:unionDefaultGraph true");
        populate(dataset);
        assertThrows(UnsupportedOperationException.class,
                () -> rows(dataset, "SELECT * { :s :p+ ?o }", null));
        Context request = Context.create();
        request.set(TransformUnionDefaultGraph.STRICT, false);
        assertEquals(1, rows(dataset, "SELECT * { :physical :p+ ?o }", request).size());
        assertFalse(dataset.getContext().isDefined(TransformUnionDefaultGraph.STRICT));
        request.set(TransformUnionDefaultGraph.STRICT, true);
        request.set(ARQ.optimization, false);
        assertEquals(3, count(dataset, request));
        assertThrows(UnsupportedOperationException.class,
                () -> rows(dataset, "SELECT * { :s :p+ ?o }", request));
        dataset.close();
    }

    @Test void directPropertiesOverrideGenericAssemblerContextAndBadBooleansAreRejected() {
        Model spec = specification("; dsg:unionDefaultGraph true ; ja:context [ ja:cxtName dsg:unionDefaultGraph ; ja:cxtValue false ]");
        DatasetGraph dataset = DatasetFactory.assemble(spec.getResource("urn:dataset")).asDatasetGraph();
        assertTrue(dataset.getContext().isTrue(ProxyDatasetSettings.UNION_DEFAULT_GRAPH));
        dataset.close();
        for (String setting : List.of("dsg:unionDefaultGraph \"true\"", "dsg:udgStrict <urn:wrong>",
                "dsg:udgDeduplicate true, false")) {
            Model bad = specification("; " + setting);
            assertThrows(org.apache.jena.assembler.exceptions.AssemblerException.class,
                    () -> DatasetFactory.assemble(bad.getResource("urn:dataset")), setting);
        }
        DatasetGraph disabled = assemble("; dsg:unionDefaultGraph true ; dsg:proxyGraphsEnabled false");
        assertFalse(new QueryEngineFactoryProxyGraphs().accept(QueryFactory.create("SELECT * {}"), disabled, null));
        disabled.close();
    }

    @Test void ordinaryRdfDatasetAssemblerCanEnableTheSameModeViaContext() {
        Model spec = specification("; ja:context [ ja:cxtName dsg:unionDefaultGraph ; ja:cxtValue true ]");
        var root = spec.getResource("urn:dataset");
        root.removeAll(org.apache.jena.vocabulary.RDF.type);
        root.addProperty(org.apache.jena.vocabulary.RDF.type, spec.createResource("http://jena.hpl.hp.com/2005/11/Assembler#RDFDataset"));
        DatasetGraph dataset = DatasetFactory.assemble(root).asDatasetGraph();
        populate(dataset);
        assertEquals(3, count(dataset, null));
        dataset.close();
    }

    @Test void initialBindingsKeepUdgSemanticsWithoutOptionalFederationOptimizations() {
        DatasetGraph dataset = assemble("; dsg:unionDefaultGraph true");
        populate(dataset);
        Query query = QueryFactory.create(PREFIX + "SELECT ?s ?o { ?s :p ?o }");
        var initial = BindingFactory.binding(Var.alloc("s"), NodeFactory.createURI("urn:s"));
        var plan = new QueryEngineFactoryProxyGraphs().create(query, dataset, initial, Context.emptyContext());
        try {
            var matches = new ArrayList<Binding>();
            plan.iterator().forEachRemaining(matches::add);
            assertEquals(1, matches.size());
            assertEquals(NodeFactory.createURI("urn:s"), matches.get(0).get(Var.alloc("s")));
            assertEquals(NodeFactory.createURI("urn:m"), matches.get(0).get(Var.alloc("o")));
        } finally { plan.close(); dataset.close(); }
    }

    @Test void assembledRemoteGraphViewsUseUdgBeforeRoutingOverHttp() {
        DatasetGraph backend = DatasetGraphFactory.create();
        RDFParser.fromString("""
            PREFIX : <urn:>
            :remote1 { :s :p :m . :dup :p :o . }
            :remote2 { :m :q :end . :dup :p :o . }
            """, Lang.TRIG).parse(backend);
        FusekiServer server = FusekiServer.create().port(0).add("/remote", backend).build();
        server.start();
        try {
            String description = """
                PREFIX ja: <http://jena.hpl.hp.com/2005/11/Assembler#>
                PREFIX dsg: <https://w3id.org/aksw/jena/dataset#>
                <urn:dataset> a dsg:DatasetProxy ; dsg:unionDefaultGraph true ;
                    ja:namedGraph [ ja:graphName <urn:g1> ; ja:graph <urn:view1> ] ;
                    ja:namedGraph [ ja:graphName <urn:g2> ; ja:graph <urn:view2> ] .
                <urn:view1> a ja:ViewGraph ; ja:dataset <urn:remote> ; ja:graphName <urn:remote1> .
                <urn:view2> a ja:ViewGraph ; ja:dataset <urn:remote> ; ja:graphName <urn:remote2> .
                <urn:remote> a dsg:DatasetHTTP ; dsg:destination "%s" .
                """.formatted("http://localhost:" + server.getPort() + "/remote");
            Model model = RDFParser.fromString(description, Lang.TURTLE).toModel();
            DatasetGraph dataset = DatasetFactory.assemble(model.getResource("urn:dataset")).asDatasetGraph();
            try {
                assertEquals(3, count(dataset, null));
                assertEquals(1, rows(dataset, "SELECT ?end { :s :p ?m . ?m :q ?end }", null).size());
                assertEquals(1, rows(dataset, "SELECT DISTINCT ?g { BIND(:s AS ?s) LATERAL { GRAPH ?g { ?s ?p ?o } } }", null).size());
                assertEquals(1, rows(dataset, "SELECT * { :dup :p :o }", null).size());
                assertEquals(2, rows(dataset, "SELECT * { ?s :p _:object }", null).size());
                Context request = Context.create();
                request.set(TransformUnionDefaultGraph.DEDUPLICATE, false);
                assertEquals(4, count(dataset, request));
                var ground = rows(dataset, "SELECT * { :dup :p :o }", request);
                assertEquals(2, ground.size());
                assertTrue(ground.stream().allMatch(Binding::isEmpty));
                request.set(ARQ.optimization, false);
                assertEquals(4, count(dataset, request));
                request.set(ProxyDatasetSettings.UNION_DEFAULT_GRAPH, false);
                assertEquals(0, count(dataset, request));
                var op = Algebra.compile(QueryFactory.create(PREFIX + "SELECT ?end { :s :p ?m . ?m :q ?end }"));
                var plan = new QueryEngineFactoryProxyGraphs().create(op, dataset,
                        BindingFactory.empty(), dataset.getContext());
                try { var it = plan.iterator(); assertTrue(it.hasNext()); assertEquals(NodeFactory.createURI("urn:end"), it.nextBinding().get(Var.alloc("end"))); }
                finally { plan.close(); }
                var named = Algebra.toQuadForm(Algebra.compile(QueryFactory.create(PREFIX +
                        "SELECT ?g ?o { GRAPH ?g { :dup :p ?o } }")));
                var quadPlan = new QueryEngineFactoryProxyGraphs().create(named, dataset,
                        BindingFactory.empty(), dataset.getContext());
                try { var matches = new ArrayList<Binding>(); quadPlan.iterator().forEachRemaining(matches::add); assertEquals(2, matches.size()); }
                finally { quadPlan.close(); }
            } finally { dataset.close(); }
        } finally { server.stop(); }
    }
}
