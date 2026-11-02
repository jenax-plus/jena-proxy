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

package org.aksw.jena.proxy.algebra.udg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.apache.jena.graph.*;
import org.apache.jena.query.*;
import org.apache.jena.riot.*;
import org.apache.jena.sparql.algebra.*;
import org.apache.jena.sparql.algebra.op.*;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.binding.*;
import org.junit.jupiter.api.Test;

/** UDG tests use local graphs and a materialized union, independent of federation planning. */
class TestTransformUnionDefaultGraph {
    private static final String PREFIX = "PREFIX : <urn:> ";
    private static final String RESOURCE = "https://example.org/baci/observation/HS92_V202601/2024-4-124-290543";

    private static Op compile(String query) {
        return Algebra.compile(QueryFactory.create(PREFIX + query, Syntax.syntaxARQ));
    }

    private static DatasetGraph fixture() {
        DatasetGraph data = DatasetGraphFactory.createGeneral();
        RDFParser.fromString("""
            PREFIX : <urn:>
            :physical :defaultOnly 999 .
            :g1 { :s :p :m ; :kind :A . :dup :p :o . :a :p :b . }
            :g2 { :m :q :end . :dup :p :o . :s :kind :B . :b :q :c . }
            :g3 { :unrelated :p :other . }
            """, Lang.TRIG).parse(data);
        data.addGraph(NodeFactory.createURI("urn:empty"), GraphMemFactory.createDefaultGraph());
        Node subject = NodeFactory.createURI(RESOURCE);
        for (String graph : List.of("urn:g1", "urn:g2")) {
            data.add(new Quad(NodeFactory.createURI(graph), subject, NodeFactory.createURI("urn:fact"),
                    NodeFactory.createURI("urn:value")));
        }
        return data;
    }

    /** Same named graphs, but a default graph made from their set union. */
    private static DatasetGraph reference(DatasetGraph source) {
        DatasetGraph result = DatasetGraphFactory.createGeneral();
        source.listGraphNodes().forEachRemaining(name -> {
            Graph copy = GraphMemFactory.createDefaultGraph();
            var triples = source.getGraph(name).find();
            try {
                triples.forEachRemaining(triple -> {
                    copy.add(triple);
                    result.getDefaultGraph().add(triple);
                });
            } finally { triples.close(); }
            result.addGraph(name, copy);
        });
        return result;
    }

    private static Map<Binding, Integer> rows(Op op, DatasetGraph data) {
        Map<Binding, Integer> result = new HashMap<>();
        var it = Algebra.exec(op, data);
        try { while (it.hasNext()) result.merge(BindingFactory.copy(it.nextBinding()), 1, Integer::sum); }
        finally { it.close(); }
        return result;
    }

    private static void sameAsUnion(String query, DatasetGraph data, boolean serialize) {
        Op original = compile(query);
        Op rewritten = TransformUnionDefaultGraph.rewrite(original);
        DatasetGraph union = reference(data);
        try {
            var expected = rows(original, union);
            assertEquals(expected, rows(rewritten, data), query + "\n" + rewritten);
            assertEquals(OpVars.visibleVars(original), OpVars.visibleVars(rewritten), "no internal graph outputs");
            assertEquals(rewritten, TransformUnionDefaultGraph.rewrite(rewritten), "idempotence");
            if (serialize) {
                Query wire = OpAsQuery.asQuery(rewritten);
                Op roundTrip = Algebra.compile(QueryFactory.create(wire.toString(), Syntax.syntaxARQ));
                assertEquals(expected, rows(roundTrip, data), "serialized: " + wire);
            }
        } finally { union.close(); }
    }

    @Test void graphInsideLateralKeepsProvenanceConstraint() {
        DatasetGraph data = fixture();
        String query = "SELECT DISTINCT ?g { BIND(<" + RESOURCE + "> AS ?s) LATERAL { GRAPH ?g { ?s ?p ?o } } }";
        Op original = compile(query);
        Op result = TransformUnionDefaultGraph.rewrite(original);
        assertEquals(original, result, "explicit GRAPH must not gain a second graph variable");
        assertEquals(Set.of(
                BindingFactory.binding(Var.alloc("g"), NodeFactory.createURI("urn:g1")),
                BindingFactory.binding(Var.alloc("g"), NodeFactory.createURI("urn:g2"))), rows(result, data).keySet());
        sameAsUnion(query, data, true);
        data.close();
    }

    @Test void tripleJoinsMayCrossNamedGraphs() {
        DatasetGraph data = fixture();
        sameAsUnion("SELECT * { :a :p ?middle . ?middle :q ?end }", data, true);
        sameAsUnion("SELECT * { ?s :p ?middle . ?middle :q ?end }", data, true);
        sameAsUnion("SELECT (COUNT(*) AS ?c) { ?s :p ?middle . ?middle :q ?end }", data, true);
        data.close();
    }

    @Test void overlappingTriplesAreSetsButQueryResultsRetainBags() {
        DatasetGraph data = fixture();
        sameAsUnion("SELECT * { :dup :p ?o }", data, true);
        sameAsUnion("SELECT ?p { ?s ?p ?o }", data, true);
        sameAsUnion("SELECT * { VALUES ?tag { 1 1 2 } :dup :p ?o }", data, true);
        sameAsUnion("SELECT ?o { { :dup :p ?o } UNION { :dup :p ?o } }", data, true);
        data.close();
    }

    @Test void physicalDefaultGraphIsExcludedAndGroundPatternsUseExistence() {
        DatasetGraph data = fixture();
        for (String query : List.of("SELECT * { :dup :p :o }", "SELECT * { :absent :p :o }",
                "SELECT * { :dup :p :o . :m :q :end }", "SELECT * { :physical :defaultOnly ?x }",
                "SELECT * {}", "SELECT * { VALUES ?x { 1 1 } :dup :p :o }")) {
            sameAsUnion(query, data, true);
        }
        data.close();
        DatasetGraph empty = DatasetGraphFactory.createGeneral();
        sameAsUnion("SELECT * { ?s ?p ?o }", empty, true);
        sameAsUnion("SELECT * {}", empty, true);
        empty.close();
    }

    @Test void namedGraphScopesAndEmptyGraphEnumerationArePreserved() {
        DatasetGraph data = fixture();
        for (String query : List.of("SELECT * { GRAPH ?g { ?s ?p ?o } }",
                "SELECT * { GRAPH :g1 { :m :q ?x } }", "SELECT * { GRAPH ?g {} }",
                "SELECT * { GRAPH ?g { :s :kind ?kind } :m :q ?end }",
                "SELECT * { GRAPH ?g { GRAPH :g1 { :dup :p ?o } } }")) {
            sameAsUnion(query, data, true);
        }
        data.close();
    }

    @Test void existsAndNotExistsRespectTheirEnclosingGraph() {
        DatasetGraph data = fixture();
        for (String query : List.of(
                "SELECT ?s { VALUES ?s { :a :s :physical } FILTER EXISTS { ?s :p ?m . ?m :q ?end } }",
                "SELECT ?s { VALUES ?s { :a :s :physical } FILTER NOT EXISTS { ?s :p ?m . ?m :q ?end } }",
                "SELECT ?g { GRAPH ?g { :s :kind ?k FILTER EXISTS { :s :p ?m } } }",
                "SELECT ?g { GRAPH ?g { :s :kind ?k FILTER NOT EXISTS { :s :p ?m } } }",
                "SELECT (EXISTS { :a :p ?m . ?m :q :c } AS ?found) {}")) {
            sameAsUnion(query, data, true);
        }
        data.close();
    }

    @Test void optionalMinusSubqueriesAndDefaultGraphLateralMatchReference() {
        DatasetGraph data = fixture();
        for (String query : List.of(
                "SELECT * { ?s :p ?m OPTIONAL { ?m :q ?end } }",
                "SELECT * { ?s :p ?m MINUS { ?m :q ?end } }",
                "SELECT ?end { { SELECT ?m { :a :p ?m } } ?m :q ?end }",
                "SELECT * { BIND(:a AS ?s) LATERAL { ?s :p ?m . ?m :q ?end } }")) {
            sameAsUnion(query, data, true);
        }
        data.close();
    }

    @Test void blankNodesSharedAcrossGraphsKeepIdentityAndBgpExistentialsJoin() {
        DatasetGraph data = fixture();
        Node blank = NodeFactory.createBlankNode();
        data.add(new Quad(NodeFactory.createURI("urn:g1"), NodeFactory.createURI("urn:blankSubject"),
                NodeFactory.createURI("urn:p"), blank));
        data.add(new Quad(NodeFactory.createURI("urn:g2"), blank, NodeFactory.createURI("urn:q"),
                NodeFactory.createURI("urn:blankEnd")));
        sameAsUnion("SELECT * { :blankSubject :p ?m . ?m :q ?end }", data, true);
        sameAsUnion("SELECT ?s ?end { ?s :p _:shared . _:shared :q ?end }", data, false);
        data.close();
    }

    @Test void freshVariablesDoNotCaptureQueryOrExpressionVariables() {
        DatasetGraph data = fixture();
        for (String query : List.of(
                "SELECT * { VALUES ?__jenaProxyUdgGraph0 { 1 } ?s :p ?o }",
                "SELECT ?s { ?s :p ?o FILTER EXISTS { VALUES ?__jenaProxyUdgGraph0 { 1 } FILTER(?__jenaProxyUdgGraph0 = 1) } }")) {
            sameAsUnion(query, data, true);
        }
        data.close();
    }

    @Test void tripleQuadPatternAndMixedQuadBlockInputsAreSupported() {
        DatasetGraph data = fixture();
        DatasetGraph union = reference(data);
        Op original = compile("SELECT * { :a :p ?m . ?m :q ?end . GRAPH ?g { :s :kind ?kind } }");
        Op quads = Algebra.toQuadForm(original);
        assertEquals(rows(original, union), rows(TransformUnionDefaultGraph.rewrite(quads), data));
        Triple triple = Triple.create(NodeFactory.createURI("urn:dup"), NodeFactory.createURI("urn:p"), Var.alloc("o"));
        assertEquals(rows(new OpTriple(triple), union), rows(TransformUnionDefaultGraph.rewrite(new OpTriple(triple)), data));
        QuadPattern pattern = new QuadPattern();
        pattern.add(new Quad(Quad.defaultGraphNodeGenerated, triple));
        pattern.add(new Quad(Var.alloc("g"), NodeFactory.createURI("urn:s"), NodeFactory.createURI("urn:kind"), Var.alloc("kind")));
        Op block = new OpQuadBlock(pattern);
        assertEquals(rows(block, union), rows(TransformUnionDefaultGraph.rewrite(block), data));
        Op quad = new OpQuad(new Quad(Quad.unionGraph, triple));
        assertEquals(rows(new OpTriple(triple), union), rows(TransformUnionDefaultGraph.rewrite(quad), data));
        union.close(); data.close();
    }

    @Test void servicesRemainOpaqueEvenInsideExpressionsAndGraphScopes() {
        for (String query : List.of(
                "SELECT * { SERVICE <urn:remote> { ?s ?p ?o } }",
                "SELECT * { SERVICE SILENT ?endpoint { ?s :p+ ?o } }",
                "SELECT ?g { GRAPH ?g { SERVICE <urn:remote> { ?s ?p ?o } } }",
                "SELECT * { FILTER EXISTS { SERVICE <urn:remote> { ?s :p+ ?o } } }")) {
            Op original = compile(query);
            assertEquals(original, TransformUnionDefaultGraph.rewrite(original));
        }
        Op mixed = compile("SELECT * { ?s :p ?o SERVICE <urn:remote> { ?s :q+ ?x } }");
        Op result = TransformUnionDefaultGraph.rewrite(mixed);
        List<OpService> services = new ArrayList<>();
        OpWalker.walk(result, new OpVisitorBase() {
            @Override public void visit(OpService service) { services.add(service); }
        });
        assertEquals(1, services.size());
        assertInstanceOf(OpPath.class, services.get(0).getSubOp());
    }

    @Test void unsupportedDefaultGraphPathsFailExplicitlyButNamedPathsRemainValid() {
        for (String query : List.of("SELECT * { ?s :p+ ?o }", "SELECT * { ?s (:p/:q)* ?o }")) {
            assertThrows(UnsupportedOperationException.class, () -> TransformUnionDefaultGraph.rewrite(compile(query)));
        }
        Op named = compile("SELECT * { GRAPH ?g { ?s :p+ ?o } }");
        assertEquals(named, TransformUnionDefaultGraph.rewrite(named));
        DatasetGraph data = fixture();
        sameAsUnion("SELECT * { GRAPH ?g { ?s :p+ ?o } }", data, true);
        data.close();
    }

    @Test void contextSwitchDefaultsToStrictAndPermissiveModeLeavesPathsUnchanged() {
        Op path = compile("SELECT * { :a :p+ ?o }");
        var context = new org.apache.jena.sparql.util.Context();
        assertThrows(UnsupportedOperationException.class, () -> TransformUnionDefaultGraph.rewrite(path));
        assertThrows(UnsupportedOperationException.class, () -> TransformUnionDefaultGraph.rewrite(path, context));
        assertFalse(context.isDefined(TransformUnionDefaultGraph.STRICT), "reading a default must not mutate context");
        context.set(TransformUnionDefaultGraph.STRICT, true);
        assertThrows(UnsupportedOperationException.class, () -> TransformUnionDefaultGraph.rewrite(path, context));
        context.set(TransformUnionDefaultGraph.STRICT, false);
        assertEquals(path, TransformUnionDefaultGraph.rewrite(path, context));
        assertTrue(context.isFalse(TransformUnionDefaultGraph.STRICT));
        // The switch is per invocation, with no leakage into the next strict request.
        assertThrows(UnsupportedOperationException.class, () -> TransformUnionDefaultGraph.rewrite(path));
        Op mixed = compile("SELECT * { ?s :p ?m . ?m :q+ ?end }");
        Op rewritten = TransformUnionDefaultGraph.rewrite(mixed, context);
        assertNotEquals(mixed, rewritten, "eligible triples still get rewritten in best-effort mode");
        List<OpPath> paths = new ArrayList<>();
        OpWalker.walk(rewritten, new OpVisitorBase() {
            @Override public void visit(OpPath op) { paths.add(op); }
        });
        assertEquals(1, paths.size());
        DatasetGraph data = fixture();
        Op physicalPath = compile("SELECT * { :physical :defaultOnly+ ?o }");
        assertEquals(rows(physicalPath, data), rows(TransformUnionDefaultGraph.rewrite(physicalPath, context), data));
        assertEquals(1, rows(physicalPath, data).size(), "unsupported paths use the physical default graph");
        data.close();
    }

    @Test void opaqueExtensionsAndActiveGraphProceduresRespectStrictness() {
        Op opaque = new OpExt("udg-test-opaque") {
            @Override public Op effectiveOp() { return compile("SELECT * { ?s ?p ?o }"); }
            @Override public org.apache.jena.sparql.engine.QueryIterator eval(
                    org.apache.jena.sparql.engine.QueryIterator input,
                    org.apache.jena.sparql.engine.ExecutionContext context) { throw new UnsupportedOperationException(); }
            @Override public int hashCode() { return System.identityHashCode(this); }
            @Override public boolean equalTo(Op op, org.apache.jena.sparql.util.NodeIsomorphismMap labels) { return this == op; }
            @Override public void outputArgs(org.apache.jena.atlas.io.IndentedWriter out,
                    org.apache.jena.sparql.serializer.SerializationContext context) { out.print("opaque"); }
        };
        var permissive = new org.apache.jena.sparql.util.Context();
        permissive.set(TransformUnionDefaultGraph.STRICT, false);
        assertThrows(UnsupportedOperationException.class, () -> TransformUnionDefaultGraph.rewrite(opaque));
        assertSame(opaque, TransformUnionDefaultGraph.rewrite(opaque, permissive));
        Node function = NodeFactory.createURI("urn:custom");
        var arg = new org.apache.jena.sparql.pfunction.PropFuncArg(Var.alloc("s"));
        for (Op op : List.of(new OpPropFunc(function, arg, arg, OpTable.unit()),
                new OpProcedure(function, new org.apache.jena.sparql.expr.ExprList(), OpTable.unit()))) {
            assertThrows(UnsupportedOperationException.class, () -> TransformUnionDefaultGraph.rewrite(op));
            assertEquals(op, TransformUnionDefaultGraph.rewrite(op, permissive));
            Op named = new OpGraph(NodeFactory.createURI("urn:g1"), op);
            assertEquals(named, TransformUnionDefaultGraph.rewrite(named));
        }
    }

    @Test void anonymousOnlyPatternsRetainSolutionMultiplicities() {
        DatasetGraph data = fixture();
        data.add(new Quad(NodeFactory.createURI("urn:g2"), NodeFactory.createURI("urn:dup2"),
                NodeFactory.createURI("urn:p"), NodeFactory.createURI("urn:o")));
        sameAsUnion("SELECT (COUNT(*) AS ?c) { _:subject :p :o }", data, false);
        sameAsUnion("SELECT * { _:subject :p :o }", data, false);
        data.close();
    }

    @Test void deduplicationDefaultsToTrueAndCanBeSkippedIndependently() {
        DatasetGraph data = fixture();
        Op query = compile("SELECT * { :dup :p ?o }");
        var context = new org.apache.jena.sparql.util.Context();
        var defaultRows = rows(TransformUnionDefaultGraph.rewrite(query, context), data);
        assertEquals(1, defaultRows.values().stream().mapToInt(Integer::intValue).sum());
        assertFalse(context.isDefined(TransformUnionDefaultGraph.DEDUPLICATE));
        context.set(TransformUnionDefaultGraph.DEDUPLICATE, true);
        assertEquals(defaultRows, rows(TransformUnionDefaultGraph.rewrite(query, context), data));
        context.set(TransformUnionDefaultGraph.DEDUPLICATE, false);
        Op bag = TransformUnionDefaultGraph.rewrite(query, context);
        assertEquals(2, rows(bag, data).values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(OpVars.visibleVars(query), OpVars.visibleVars(bag));
        assertEquals(rows(bag, data), rows(Algebra.compile(QueryFactory.create(OpAsQuery.asQuery(bag).toString())), data));
        assertTrue(context.isFalse(TransformUnionDefaultGraph.DEDUPLICATE));
        assertFalse(context.isDefined(TransformUnionDefaultGraph.STRICT));
        assertThrows(UnsupportedOperationException.class, () -> TransformUnionDefaultGraph.rewrite(
                compile("SELECT * { ?s :p+ ?o }"), context), "bag mode must not disable strictness");
        Op distinct = compile("SELECT DISTINCT ?o { :dup :p ?o }");
        assertEquals(rows(TransformUnionDefaultGraph.rewrite(distinct), data), rows(TransformUnionDefaultGraph.rewrite(distinct, context), data),
                "query-level DISTINCT is still honored");
        assertEquals(defaultRows, rows(TransformUnionDefaultGraph.rewrite(query), data), "no option leakage");
        data.close();
    }

    @Test void bagModeRetainsGroundMatchesAndMultipliesTripleJoinContributions() {
        DatasetGraph data = fixture();
        var context = new org.apache.jena.sparql.util.Context();
        context.set(TransformUnionDefaultGraph.DEDUPLICATE, false);
        Op ground = compile("SELECT * { :dup :p :o }");
        var groundRows = rows(TransformUnionDefaultGraph.rewrite(ground, context), data);
        assertEquals(2, groundRows.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(groundRows.keySet().stream().allMatch(Binding::isEmpty));
        Op incoming = compile("SELECT * { VALUES ?tag { 1 1 } :dup :p :o }");
        assertEquals(4, rows(TransformUnionDefaultGraph.rewrite(incoming, context), data)
                .values().stream().mapToInt(Integer::intValue).sum());
        for (String graph : List.of("urn:g1", "urn:g2")) {
            data.add(new Quad(NodeFactory.createURI(graph), NodeFactory.createURI("urn:o"),
                    NodeFactory.createURI("urn:q"), NodeFactory.createURI("urn:end")));
        }
        Op join = compile("SELECT * { :dup :p ?o . ?o :q ?end }");
        assertEquals(4, rows(TransformUnionDefaultGraph.rewrite(join, context), data)
                .values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(1, rows(TransformUnionDefaultGraph.rewrite(join), data)
                .values().stream().mapToInt(Integer::intValue).sum());
        data.close();
    }

    @Test void disjointGraphContentsKeepTheSameResultsWithoutDistinctOperators() {
        DatasetGraph data = DatasetGraphFactory.createGeneral();
        RDFParser.fromString("PREFIX : <urn:> :g1 { :a :p :b . } :g2 { :b :q :c . }", Lang.TRIG).parse(data);
        var context = new org.apache.jena.sparql.util.Context();
        context.set(TransformUnionDefaultGraph.DEDUPLICATE, false);
        for (String text : List.of("SELECT * { ?s ?p ?o }", "SELECT * { :a :p ?m . ?m :q ?end }")) {
            Op query = compile(text);
            Op bag = TransformUnionDefaultGraph.rewrite(query, context);
            assertEquals(rows(TransformUnionDefaultGraph.rewrite(query), data), rows(bag, data));
            OpWalker.walk(bag, new OpVisitorBase() {
                @Override public void visit(OpDistinct distinct) { fail("bag rewrite must not introduce DISTINCT"); }
            });
        }
        data.close();
    }

    @Test void generatedPartitionedDatasetsMatchMaterializedUnion() {
        Random random = new Random(5421);
        List<String> queries = List.of("SELECT * { ?s :p ?o }", "SELECT ?p { ?s ?p ?o }",
                "SELECT * { ?s :p ?m . ?m :q ?end }", "SELECT (COUNT(*) AS ?c) { ?s ?p ?o }",
                "SELECT * { ?s :p ?m OPTIONAL { ?m :q ?end } }",
                "SELECT ?s { ?s :p ?m FILTER EXISTS { ?m :q ?end } }");
        for (int run = 0; run < 60; run++) {
            DatasetGraph data = DatasetGraphFactory.createGeneral();
            for (int graph = 0; graph < 4; graph++) {
                Node name = NodeFactory.createURI("urn:graph" + graph);
                data.addGraph(name, GraphMemFactory.createDefaultGraph());
                for (int n = 0; n < 10; n++) {
                    data.add(new Quad(name, NodeFactory.createURI("urn:n" + random.nextInt(4)),
                            NodeFactory.createURI(random.nextBoolean() ? "urn:p" : "urn:q"),
                            NodeFactory.createURI("urn:n" + random.nextInt(4))));
                }
            }
            for (String query : queries) sameAsUnion(query, data, false);
            data.close();
        }
    }
}
