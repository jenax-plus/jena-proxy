package org.aksw.jena.proxy;

import org.aksw.jena.rdflink.dataset.DatasetGraphOverRDFLink;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdflink.RDFLinkHTTP;
import org.apache.jena.sparql.algebra.Table;
import org.apache.jena.sparql.core.DatasetGraphMapLink;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.exec.RowSetOps;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.jupiter.api.Test;

public class TestGraphView {
    @Test
    public void test() {
        DatasetGraphOverRDFLink dsg1 = DatasetGraphOverRDFLink.newBuilder()
            //.linkCreator(() -> RDFLinkHTTP.service("https://data.inf-ai.duckdns.org/provider-faostat-ontop/").build())
            .linkCreator(() -> RDFLinkHTTP.service("http://sparql-baci-ontop.k8s.internal/sparql").build())
            .supportsTransactions(false)
            .supportsTransactionAbort(false)
            .build();

//        try (QueryExec qe =
//                QueryExecHTTP.service("http://sparql-faostat-ontop.k8s.internal/sparql")
//                //QueryExecHTTP.service("https://dbpedia.org/sparql")
//                .query("SELECT * { ?s ?p ?o }").build()) {
//            qe.select().stream().limit(10).forEach(System.out::println);
//        }

        DatasetGraphOverRDFLink dsg2 = DatasetGraphOverRDFLink.newBuilder()
                .linkCreator(() -> RDFLinkHTTP.service("http://sparql-faostat-ontop.k8s.internal/sparql").build())
                .supportsTransactions(false)
                .supportsTransactionAbort(false)
                .build();

        DatasetGraphOverRDFLink dsg3 = DatasetGraphOverRDFLink.newBuilder()
                .linkCreator(() -> RDFLinkHTTP.service("http://sparql-tiva-ontop.k8s.internal/sparql").build())
                .supportsTransactions(false)
                .supportsTransactionAbort(false)
                .build();

        DatasetGraphMapLink dsg = new DatasetGraphMapLink(GraphFactory.createDefaultGraph());
        // TODO Accidentally using GraphView.createUnionGraph causes hard to debug exceptions downstream - should be handled early!
        dsg.addGraph(NodeFactory.createURI("https://example.org/g1"), GraphView.createDefaultGraph(dsg1));
        dsg.addGraph(NodeFactory.createURI("https://example.org/g2"), GraphView.createDefaultGraph(dsg2));
        dsg.addGraph(NodeFactory.createURI("https://example.org/g3"), GraphView.createDefaultGraph(dsg3));

        // Table table = QueryExec.dataset(dsg).query("SELECT * { GRAPH ?g { SELECT (COUNT(*) AS ?c) { ?s ?p ?o } } }").table();
        // Table table = QueryExec.dataset(dsg).query("SELECT (count(*) AS ?c) { GRAPH ?g { ?s ?p ?o } }").table();
        // Table table = QueryExec.dataset(dsg).query("SELECT * { GRAPH ?g { } }").table();
        //Table table = QueryExec.dataset(dsg).query("SELECT * { GRAPH ?g { ?s ?p ?o } } LIMIT 10").table();

        // Table table = QueryExec.dataset(dsg).query("SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s ?p ?o } }").table();

        // Table table = QueryExec.dataset(dsg).query("SELECT DISTINCT ?p { GRAPH ?g { ?s ?p ?o } }").table();
        Table table = QueryExec.dataset(dsg).query("SELECT * { GRAPH ?g { <https://example.org/baci/observation/HS92_V202601/2024-100-76-071230> ?p ?o } } LIMIT 1000").table();
        RowSetOps.out(System.out, table.toRowSet());
    }
}
