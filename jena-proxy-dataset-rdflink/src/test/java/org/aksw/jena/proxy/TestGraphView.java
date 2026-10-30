package org.aksw.jena.proxy;

import org.aksw.jena.rdflink.dataset.DatasetGraphOverRDFLink;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.rdflink.RDFLinkHTTP;
import org.apache.jena.sparql.algebra.Table;
import org.apache.jena.sparql.core.DatasetGraphMapLink;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.exec.QueryExec;
import org.apache.jena.sparql.exec.RowSetOps;
import org.apache.jena.sparql.exec.http.QuerySendMode;
import org.apache.jena.sparql.graph.GraphFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class TestGraphView {
    @Test
    @Disabled("Comment this annotation out to test against live endpoints.")
    public void test() {
        DatasetGraphOverRDFLink dsg1 = DatasetGraphOverRDFLink.newBuilder()
            .linkCreator(() -> RDFLinkHTTP.service("https://www.govdata.de/sparql").querySendMode(QuerySendMode.asPostForm).build())
            .build();

        DatasetGraphOverRDFLink dsg2 = DatasetGraphOverRDFLink.newBuilder()
            .linkCreator(() -> RDFLinkHTTP.service("https://dbpedia.org/sparql").build())
            .build();

        DatasetGraphOverRDFLink dsg3 = DatasetGraphOverRDFLink.newBuilder()
            .linkCreator(() -> RDFLinkHTTP.service("https://sparql.dnb.de/api/dnbgnd").build())
            .build();

        DatasetGraphMapLink dsg = new DatasetGraphMapLink(GraphFactory.createDefaultGraph());
        // XXX Accidentally using GraphView.createUnionGraph causes hard to debug exceptions downstream - should be handled early!
        dsg.addGraph(NodeFactory.createURI("https://example.org/govdata.de"), GraphView.createDefaultGraph(dsg1));
        dsg.addGraph(NodeFactory.createURI("https://example.org/dbpedia"), GraphView.createNamedGraph(dsg2, NodeFactory.createURI("http://dbpedia.org")));
        dsg.addGraph(NodeFactory.createURI("https://example.org/dnb"), GraphView.createDefaultGraph(dsg3));

        Table table = QueryExec.dataset(dsg).query("SELECT (COUNT(*) AS ?c) { GRAPH ?g { ?s a ?o } }").table();

        // Table table = QueryExec.dataset(dsg).query("SELECT DISTINCT ?p { GRAPH ?g { ?s ?p ?o } }").table();
        // Table table = QueryExec.dataset(dsg).query("SELECT * { GRAPH ?g { <https://example.org/baci/observation/HS92_V202601/2024-100-76-071230> ?p ?o } } LIMIT 1000").table();
        // Table table = QueryExec.dataset(dsg).query("SELECT * { GRAPH ?g { SELECT (COUNT(*) AS ?c) { ?s ?p ?o } } }").table();
        // Table table = QueryExec.dataset(dsg).query("SELECT (count(*) AS ?c) { GRAPH ?g { ?s ?p ?o } }").table();
        // Table table = QueryExec.dataset(dsg).query("SELECT * { GRAPH ?g { } }").table();
        //Table table = QueryExec.dataset(dsg).query("SELECT * { GRAPH ?g { ?s ?p ?o } } LIMIT 10").table();

        RowSetOps.out(System.out, table.toRowSet());
    }
}
