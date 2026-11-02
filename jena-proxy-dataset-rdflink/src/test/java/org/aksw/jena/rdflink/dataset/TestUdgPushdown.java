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
import org.aksw.jena.proxy.algebra.udg.TransformUnionDefaultGraph;
import org.aksw.jena.sparql.core.remote.DsgSparqlExecutor;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.graph.*;
import org.apache.jena.query.*;
import org.apache.jena.rdflink.*;
import org.apache.jena.riot.*;
import org.apache.jena.sparql.core.*;
import org.apache.jena.sparql.engine.binding.*;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.util.Context;
import org.junit.jupiter.api.Test;

class TestUdgPushdown {
    private static final String PREFIX="PREFIX : <urn:> ";
    private static final String DATA="""
        PREFIX : <urn:>
        :left { :s1 :pC "a" ; :pD 1 . :s3 :pC "b" . :s5 :pA 1 . :s6 :pE 1 . }
        :right { :s1 :pC "a" . :s2 :pB "a" . :s4 :pB "b" . :s5 :pA 2 . :s6 :pE 1.0 . }
        """;
    private static final class Fixture implements AutoCloseable {
        final DatasetGraph backend=DatasetGraphFactory.create();
        final DatasetGraph dataset=DatasetGraphFactory.createGeneral();
        final DatasetGraph reference=DatasetGraphFactory.createGeneral();
        final List<Query> sent=new ArrayList<>();
        final FusekiServer server;
        Fixture(boolean http,boolean dedup) {
            RDFParser.fromString(DATA,Lang.TRIG).parse(backend);
            server=http?FusekiServer.create().port(0).add("/data",backend).build():null;
            if(server!=null)server.start();
            DatasetGraphOverRDFLink remote=new DatasetGraphOverRDFLink(DsgSparqlExecutor.DEFAULT,false,false) {
                @Override public RDFLink newLink() {
                    RDFLink delegate=http?RDFLinkHTTP.newBuilder().destination("http://localhost:"+server.getPort()+"/data").build()
                            :RDFLink.connect(new DatasetGraphWrapper(backend){@Override public void close(){}});
                    return (RDFLink)Proxy.newProxyInstance(RDFLink.class.getClassLoader(),new Class<?>[]{RDFLink.class},(object,method,args)->{
                        if(method.getName().equals("query")&&args[0] instanceof Query query)sent.add(query.cloneQuery());
                        try{return method.invoke(delegate,args);}catch(InvocationTargetException e){throw e.getCause();}
                    });
                }
                @Override public Iterator<Quad> find(Node g,Node s,Node p,Node o){throw new AssertionError("proxy find fallback");}
                @Override public Iterator<Quad> findNG(Node g,Node s,Node p,Node o){throw new AssertionError("proxy findNG fallback");}
            };
            for(String name:List.of("urn:left","urn:right")) {
                Node graph=NodeFactory.createURI(name);
                dataset.addGraph(graph,remote.getGraph(graph));
                backend.getGraph(graph).find().forEachRemaining(t->reference.getDefaultGraph().add(t));
            }
            dataset.getContext().set(ProxyDatasetSettings.UNION_DEFAULT_GRAPH,true);
            dataset.getContext().set(TransformUnionDefaultGraph.DEDUPLICATE,dedup);
        }
        @Override public void close(){dataset.close();reference.close();if(server!=null)server.stop();else backend.close();}
    }
    private static List<Binding> rows(DatasetGraph data,String query,Context context) {
        var builder=QueryExec.dataset(data).query(PREFIX+query);
        if(context!=null)builder.context(context);
        try(var exec=builder.build()) {List<Binding> result=new ArrayList<>();exec.select().forEachRemaining(b->result.add(BindingFactory.copy(b)));return result;}
    }
    private static Map<Binding,Integer> bag(List<Binding> rows) {
        Map<Binding,Integer> result=new HashMap<>();rows.forEach(b->result.merge(b,1,Integer::sum));return result;
    }
    private static Set<Var> vars(String... names) {
        Set<Var> result=new HashSet<>();for(String name:names)result.add(Var.alloc(name));return result;
    }
    private static void orderedRequests(Fixture f,Set<Var> columns) {
        assertEquals(2,f.sent.size());
        for(Query query:f.sent) {
            assertTrue(query.hasOrderBy(),query.toString());
            assertEquals(columns,new HashSet<>(query.getProjectVars()),query.toString());
            assertFalse(query.hasLimit(),"global slice must not become per-service LIMIT");
        }
    }
    @Test void udgScansUseSortedPartialResultsLocallyAndOverHttp() {
        for(boolean http:List.of(false,true))for(boolean dedup:List.of(false,true))try(Fixture f=new Fixture(http,dedup)) {
            var result=rows(f.dataset,"SELECT * {?s ?p ?o} ORDER BY ?s",null);
            orderedRequests(f,vars("s","p","o"));
            assertEquals(dedup?9:10,result.size());
            assertEquals(result.stream().map(b->b.get(Var.alloc("s")).getURI()).sorted().toList(),
                    result.stream().map(b->b.get(Var.alloc("s")).getURI()).toList());
            if(dedup)assertEquals(bag(rows(f.reference,"SELECT * {?s ?p ?o}",null)),bag(result));
        }
    }
    @Test void narrowDistinctRetainsHiddenSortKeysOnWireOnly() {
        for(boolean http:List.of(false,true))for(boolean dedup:List.of(false,true))try(Fixture f=new Fixture(http,dedup)) {
            var result=rows(f.dataset,"SELECT DISTINCT ?p {?s ?p ?o} ORDER BY ?s",null);
            orderedRequests(f,vars("p","s"));
            assertEquals(5,result.size());
            assertTrue(result.stream().allMatch(b->b.size()==1&&b.contains(Var.alloc("p"))));
            var names=result.stream().map(b->b.get(Var.alloc("p")).getURI()).toList();
            assertEquals(Set.of("urn:pC","urn:pD"),new HashSet<>(names.subList(0,2)));
            assertEquals(List.of("urn:pB","urn:pA","urn:pE"),names.subList(2,5));
        }
    }
    @Test void ordinaryProjectionPreservesTriplesNotJustPredicateMultiplicity() {
        for(boolean dedup:List.of(false,true))try(Fixture f=new Fixture(false,dedup)) {
            var result=rows(f.dataset,"SELECT ?p {?s ?p ?o} ORDER BY ?s",null);
            orderedRequests(f,dedup?vars("s","p","o"):vars("s","p"));
            assertEquals(dedup?9:10,result.size());
            assertTrue(result.stream().allMatch(b->b.size()==1));
        }
    }
    @Test void projectedDistinctAndGlobalTopNLimitOffsetAreDelegated() {
        for(boolean dedup:List.of(false,true))try(Fixture f=new Fixture(true,dedup)) {
            var result=rows(f.dataset,"SELECT DISTINCT ?p {?s ?p ?o} ORDER BY ?p LIMIT 3 OFFSET 1",null);
            orderedRequests(f,vars("p"));
            assertEquals(List.of("urn:pB","urn:pC","urn:pD"),result.stream().map(b->b.get(Var.alloc("p")).getURI()).toList());
            f.sent.clear();
            result=rows(f.dataset,"SELECT DISTINCT ?p {?s ?p ?o}",null);
            assertEquals(5,result.size());
            assertTrue(f.sent.stream().allMatch(q->new HashSet<>(q.getProjectVars()).equals(vars("p"))));
            assertTrue(f.sent.stream().allMatch(q->q.toString().contains("DISTINCT")));
        }
    }
    @Test void mixedLocalRemoteOrderingAndConfiguredSpillFallback() {
        try(Fixture f=new Fixture(false,true)) {
            Node local=NodeFactory.createURI("urn:local");Graph graph=GraphMemFactory.createDefaultGraph();
            graph.add(Triple.create(NodeFactory.createURI("urn:s0"),NodeFactory.createURI("urn:pZ"),NodeFactory.createURI("urn:o")));
            f.dataset.addGraph(local,graph);
            var result=rows(f.dataset,"SELECT * {?s ?p ?o} ORDER BY DESC(?s)",null);
            orderedRequests(f,vars("s","p","o"));
            assertEquals(10,result.size());assertEquals(NodeFactory.createURI("urn:s0"),result.get(result.size()-1).get(Var.alloc("s")));
            f.sent.clear();Context context=Context.create();context.set(ARQ.spillToDiskThreshold,1);
            result=rows(f.dataset,"SELECT * {?s ?p ?o} ORDER BY ?s",context);
            assertEquals(10,result.size());assertTrue(f.sent.stream().noneMatch(Query::hasOrderBy));
            assertEquals(result.stream().map(b->b.get(Var.alloc("s")).getURI()).sorted().toList(),
                    result.stream().map(b->b.get(Var.alloc("s")).getURI()).toList());
        }
    }
    @Test void partialDistinctUsesWiderKeyButBagProjectionRetainsDuplicateKeys() {
        for (boolean dedup : List.of(false, true)) try (Fixture f = new Fixture(false, dedup)) {
            f.backend.getGraph(NodeFactory.createURI("urn:left")).add(Triple.create(
                    NodeFactory.createURI("urn:s1"), NodeFactory.createURI("urn:pC"),
                    NodeFactory.createLiteralString("extra")));
            var result = rows(f.dataset, "SELECT DISTINCT ?p {?s ?p ?o} ORDER BY ?s", null);
            orderedRequests(f, vars("p", "s"));
            assertEquals(5, result.size());
            for (Query query : f.sent) {
                try (var exec = QueryExec.dataset(f.backend).query(query).build()) {
                    List<Binding> partial = new ArrayList<>();
                    exec.select().forEachRemaining(b -> partial.add(BindingFactory.copy(b)));
                    assertEquals(partial.size(), new HashSet<>(partial).size(), query.toString());
                }
            }
            f.sent.clear();
            result = rows(f.dataset, "SELECT ?p {?s ?p ?o} ORDER BY ?s", null);
            orderedRequests(f, dedup ? vars("s", "p", "o") : vars("s", "p"));
            assertEquals(dedup ? 10 : 11, result.size());
        }
    }
    @Test void outerValuesMultiplicityAndEmptyRemoteBranchRemainCorrect() {
        try (Fixture f = new Fixture(false, true)) {
            f.backend.getGraph(NodeFactory.createURI("urn:right")).clear();
            var result = rows(f.dataset,
                    "SELECT ?s ?p ?o {VALUES ?copy {1 1} ?s ?p ?o} ORDER BY DESC(?s) ?p", null);
            assertEquals(10, result.size());
            // Two VALUES rows each evaluate the two source branches. This join
            // deliberately retains ordinary local sorting/correlated execution.
            assertEquals(4, f.sent.size());
            assertEquals(Set.of(2), new HashSet<>(bag(result).values()));
            assertEquals(result.stream().map(b -> b.get(Var.alloc("s")).getURI()).sorted(Comparator.reverseOrder()).toList(),
                    result.stream().map(b -> b.get(Var.alloc("s")).getURI()).toList());
        }
    }
    @Test void unsupportedExpressionsAndOptimizerOptOutKeepLocalSorting() {
        try(Fixture f=new Fixture(false,false)) {
            rows(f.dataset,"SELECT * {?s ?p ?o} ORDER BY STR(?s)",null);
            assertTrue(f.sent.stream().noneMatch(Query::hasOrderBy));
            f.sent.clear();Context context=Context.create();context.set(ARQ.optimization,false);
            assertEquals(10,rows(f.dataset,"SELECT * {?s ?p ?o} ORDER BY ?s",context).size());
            assertTrue(f.sent.stream().noneMatch(Query::hasOrderBy));
        }
    }
}
