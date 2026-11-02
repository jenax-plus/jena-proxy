# jena-proxy

Transparent remote datasets and profile-free SPARQL federation for Apache Jena.
Remote services become graphs in a virtual dataset, with query rewrites that push
computation to the services and combine results locally.

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Maven Central](https://img.shields.io/maven-central/v/org.aksw.jena.proxy/jena-proxy-parent.svg)](https://maven-badges.herokuapp.com/maven-central/org.aksw.jena.proxy)

## Overview

jena-proxy provides **Proxy pattern** implementations for Apache Jena `DatasetGraph` that transparently forward operations to remote endpoints:

- **jena-proxy-dataset-sparql**: DatasetGraph over SPARQL Query/Update protocol
- **jena-proxy-dataset-rdflink**: DatasetGraph over Apache Jena RDFLink

These modules allow you to work with remote datasets as if they were local, while all SPARQL operations are executed against remote endpoints.

## Features

- **Transparent remote access**: Use standard Jena `DatasetGraph` API
- **SPARQL protocol support**: Query and update via HTTP SPARQL endpoints
- **RDFLink integration**: Use Apache Jena's modern RDFLink interface
- **Profile-free federation**: Best-effort query rewrites push aggregation, DISTINCT, and sorting to remote services without requiring precomputed data profiles; see the [federation feature summary](#federation-feature-summary).
- **Configurable transactions**: Enable/disable transactional support
- **HTTP authentication**: Basic authentication support
- **Jena assembler support**: Configure via Turtle configuration files

## Modules

### jena-proxy-dataset-sparql

A `DatasetGraph` implementation that forwards all operations to a remote endpoint via the SPARQL Query/Update protocol.

**Key classes:**
- `DatasetGraphSparql` - Base implementation
- `DsgSparqlExecutor` - SPARQL execution strategy
- `DsgSparqlExecutorImpl` - Default executor

### jena-proxy-dataset-rdflink

A `DatasetGraph` implementation that uses Apache Jena's `RDFLink` interface to connect to remote endpoints.

**Key classes:**
- `DatasetGraphOverRDFLink` - Main implementation
- `DatasetAssemblerHTTP` - Jena assembler for HTTP configuration
- `JenaPluginDatasetGraphOverRDFLink` - Auto-registration plugin

## Draft algebra rewrites

The SPARQL module includes an independent, opt-in `org.aksw.jena.proxy.algebra`
package for aggregate-over-union splitting, group delegation to services, and
pulling extensions above operators. See [rules, policies and composition](jena-proxy-dataset-sparql/README-algebra.md).
The composite proxy query engine applies conservative count splitting and group delegation by default.
See [AI_SUMMARY.md](AI_SUMMARY.md) for reproduced OpAsQuery limitations and suggested upstream fixes.

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>org.aksw.jena.proxy</groupId>
    <artifactId>jena-proxy-dataset-rdflink</artifactId>
    <version>0.8.1</version>
</dependency>
```

### Programmatic API

```java
import org.apache.jena.atlas.lib.Creator;
import org.apache.jena.rdflink.RDFLink;
import org.apache.jena.rdflink.RDFLinkHTTP;
import org.apache.jena.sparql.core.DatasetGraph;

import org.aksw.jena.rdflink.dataset.DatasetGraphOverRDFLink;

// Create an RDFLink to a remote endpoint
Creator<RDFLink> linkCreator = () -> RDFLinkHTTP.newBuilder()
    .queryEndpoint("http://localhost:3030/dataset/query")
    .updateEndpoint("http://localhost:3030/dataset/update")
    .gspEndpoint("http://localhost:3030/dataset/data")
    .build();

// Create a DatasetGraph proxy
DatasetGraph dsg = DatasetGraphOverRDFLink.create(linkCreator);

// Use it like a local dataset
dsg.getDefaultGraph().contains(...);
```

### Jena Assembler Configuration

```turtle
@prefix dsg: <https://w3id.org/aksw/jena/dataset#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .

<#dataset> a dsg:DatasetHTTP ;
    dsg:destination "http://localhost:3030/dataset" ;
    dsg:auth [
        dsg:user "username" ;
        dsg:pass "password" ;
    ] .
```

### Proxy graph views and a composite dataset

`ja:ViewGraph` can select a graph from a `dsg:DatasetHTTP` dataset. A query
against that model (wrapped by Jena in `DatasetGraphOne`) is delegated through
its backing `RDFLink`. The selected remote named graph becomes the query's
default graph using a SPARQL `FROM` clause. A view without `ja:graphName` uses
the endpoint's default graph. This works with both `RDFLinkDataset` and
`RDFLinkHTTP`; HTTP-specific dataset parameters are not required.

This complete Fuseki configuration combines Wikidata, DBpedia and the Deutsche
Nationalbibliothek (DNB), exposing one SPARQL query endpoint at
`http://localhost:3030/federation/sparql`. Put jena-proxy on Fuseki's classpath
and save the configuration as `federation.ttl`:

```turtle
@prefix fuseki: <http://jena.apache.org/fuseki#> .
@prefix ja:     <http://jena.hpl.hp.com/2005/11/Assembler#> .
@prefix dsg:    <https://w3id.org/aksw/jena/dataset#> .

[] a fuseki:Server ;
    fuseki:services ( <#service> ) .

<#service> a fuseki:Service ;
    fuseki:name "federation" ;
    fuseki:endpoint [
        fuseki:operation fuseki:query ;
        fuseki:name "sparql"
    ] ;
    fuseki:dataset <#dataset> .

<#dataset> a dsg:DatasetProxy ;
    dsg:unionDefaultGraph true ;
    ja:namedGraph [ ja:graphName <urn:service:wikidata> ; ja:graph <#wikidataView> ] ;
    ja:namedGraph [ ja:graphName <urn:service:dbpedia> ; ja:graph <#dbpediaView> ] ;
    ja:namedGraph [ ja:graphName <urn:service:dnb> ; ja:graph <#dnbView> ] .

<#wikidataView> a ja:ViewGraph ;
    ja:dataset <#wikidata> .

<#dbpediaView> a ja:ViewGraph ;
    ja:dataset <#dbpedia> ;
    ja:graphName <http://dbpedia.org> .

<#dnbView> a ja:ViewGraph ;
    ja:dataset <#dnb> .

<#wikidata> a dsg:DatasetHTTP ;
    dsg:destination "https://query.wikidata.org/sparql" ;
    dsg:querySendMode "asPostForm" .
<#dbpedia> a dsg:DatasetHTTP ;
    dsg:destination "https://dbpedia.org/sparql" .
<#dnb> a dsg:DatasetHTTP ;
    dsg:destination "https://sparql.dnb.de/api/dnbgnd" .
```

Start Fuseki with:

```bash
fuseki-server --config=federation.ttl
```

Only the query operation is configured; there are no update, upload or Graph Store
endpoints. See [Fuseki endpoint configuration](https://jena.apache.org/documentation/fuseki2/fuseki-config-endpoint)
for the service syntax.

These use the documented [Wikidata endpoint](https://www.mediawiki.org/wiki/Wikidata_Query_Service/User_Manual#SPARQL_endpoint),
[DBpedia endpoint and graph](https://www.dbpedia.org/resources/sparql/), and
[DNB machine endpoint](https://wiki.dnb.de/pages/viewpage.action?navigatingVersions=true&pageId=542035309).

Submit this query to `/federation/sparql` to retrieve facts about Goethe using
each dataset's resource IRI in a `GRAPH`(!) block:

```sparql
SELECT ?service ?resource ?p ?o {
    VALUES (?service ?resource) {
        (<urn:service:wikidata> <http://www.wikidata.org/entity/Q5879>)
        (<urn:service:dbpedia> <http://dbpedia.org/resource/Johann_Wolfgang_von_Goethe>)
        (<urn:service:dnb> <http://d-nb.info/gnd/118540238>)
    }
    GRAPH ?service { ?resource ?p ?o }
}
```

Remove a `VALUES` row to omit that service; unselected services receive no request.
See the [federation feature summary](#federation-feature-summary) for supported
rewrites and their limitations.

#### Technical details

- **Graph scope:** Aliases name local graph views. DBpedia requests select
  `FROM <http://dbpedia.org>`; Wikidata and DNB use their endpoint's default graph.
  A constant `GRAPH <urn:service:dbpedia>` targets only DBpedia.
- **Delegation:** Eligible graph patterns become internal `SERVICE` operations
  executed through `RDFLink`, avoiding `Graph.find()` and `DatasetGraph.find()`.
  Internal service IRIs identify routes; user-written `SERVICE` bodies retain
  their own dataset scope.
- **Local execution:** Joins between graphs run in Jena; correlated joins may issue
  additional requests per input binding. Nested graph switches resolve against
  the composite dataset and may fall back to `find()` and additional requests.
- **Request settings:** Planning copies the execution request context and preserves
  the configured optimizer without modifying the dataset context. Non-empty
  initial bindings skip optional federation optimizations; UDG semantics still apply.

#### Union default graph options

`dsg:DatasetProxy` assembles an ordinary `DatasetGraphMapLink`, using the same
`ja:defaultGraph` and `ja:namedGraph` properties as `ja:RDFDataset`. Its additional
properties set dataset context options:

| Property | Default | Effect |
| --- | --- | --- |
| `dsg:unionDefaultGraph` | `false` | Rewrites default-graph queries over the union of named graphs, including local graphs. |
| `dsg:udgStrict` | `true` | Errors on unsupported UDG operations, such as default-graph property paths. Set false for best-effort handling. |
| `dsg:udgDeduplicate` | `true` | Removes cross-graph triple duplicates. Set false for streaming scans over disjoint graph contents; overlapping graphs then contribute duplicates. |
| `dsg:proxyGraphsEnabled` | Automatic dispatch when proxy views are present | Set false to opt out of the composite engine, including UDG handling. |

Explicit properties override `ja:context` entries on the dataset; request context
settings override dataset defaults. UDG runs before graph routing, even when ARQ
optimization is disabled. Without `dsg:unionDefaultGraph true`, proxy graph routing
still activates automatically as before.

An existing `ja:RDFDataset` can enable the same mode through its context:

```turtle
<#dataset> ja:context [
    ja:cxtName dsg:unionDefaultGraph ;
    ja:cxtValue true
] .
```

The programmatic enable symbol is `ProxyDatasetSettings.UNION_DEFAULT_GRAPH`;
strictness and deduplication use `TransformUnionDefaultGraph.STRICT` and
`TransformUnionDefaultGraph.DEDUPLICATE`. See [UDG details](jena-proxy-dataset-sparql/README-udg.md).

#### Union default graph queries expected to work

With UDG enabled and optimization on, the following single-pattern queries are
expected to work over eligible proxy services. The expected execution shapes also
serve as regression-test hints. Cross-graph triple deduplication defaults to on.

| Query | Remote work | Client work |
| --- | --- | --- |
| `SELECT * { ?s ?p ?o } ORDER BY ?s` | Sort each service's matches by `?s`. | Merge sorted streams and remove cross-graph triple duplicates. |
| `SELECT DISTINCT ?p { ?s ?p ?o }` | Project to `?p` and eliminate duplicates per service. | Eliminate remaining cross-service duplicates. |
| `SELECT DISTINCT ?p { ?s ?p ?o } ORDER BY ?s` | Project to `?p ?s`, eliminate duplicate pairs and sort by `?s`. | Merge on `?s`, project to `?p`, then apply final DISTINCT. |
| `SELECT ?p { ?s ?p ?o } ORDER BY ?s` | Sort full triples; with UDG deduplication off, transfer only `?p ?s`. | Merge, finish any triple deduplication, then project to `?p`; preserve repeated predicates from different triples. |
| `SELECT DISTINCT ?p { ?s ?p ?o } ORDER BY ?p LIMIT 10 OFFSET 5` | Project, deduplicate and sort by `?p`. | Merge and deduplicate globally before applying OFFSET/LIMIT. |

For regression tests, check result multiplicities, ordering and captured remote
queries; include overlapping graphs and both UDG deduplication settings. Rows
with equal sort keys need not have a fixed tie order.

General joins and computed sort keys such as `ORDER BY STR(?s)` retain local
sorting; UDG-wide `COUNT(*)` is not yet split into partial remote counts.
Endpoints must provide compatible term ordering. A finite DISTINCT spill threshold
currently disables optional order pushdown pending a Jena fix; see
[sorting limits and spill fallback](jena-proxy-dataset-sparql/README-udg.md#sorting-and-projected-distinct).

#### Disabling federation

Federation is enabled automatically. To disable composite proxy dispatch, set this
context property on the outer dataset:

```turtle
<#dataset> ja:context [
    ja:cxtName <https://w3id.org/aksw/jena/proxy#proxyGraphsEnabled> ;
    ja:cxtValue false
] .
```

Programmatically, set `QueryEngineFactoryProxyGraphs.ENABLED` to `false` on the
dataset or execution request context. Both query-engine `accept` overloads then
decline, allowing ordinary Jena engine selection. A request setting overrides the
dataset setting.

#### Scope and limitations

- Composite dispatch supports Jena's `DatasetGraphMapLink` (including the
  `ja:RDFDataset` and `dsg:DatasetProxy` assemblers) with proxy named graphs and a local default graph.
  Local named graphs can coexist with proxy graphs.
- Single graph views support SELECT, ASK and CONSTRUCT, including subqueries,
  expressions and property paths. The view has no named graphs of its own.
- Explicit input `FROM`/`FROM NAMED` clauses and DESCRIBE are rejected explicitly.
  DESCRIBE requires a separate solution because ARQ's description handlers
  access the graph outside the query plan; use CONSTRUCT for viewer queries.
- Arbitrary graph/dataset wrappers and inference graphs are not unwrapped,
  because doing so could bypass their semantics. UDG support uses query rewriting;
  arbitrary graph-union wrappers are not specially recognized.
- Rewriting operates on Jena SPARQL syntax; opaque endpoint-specific query text
  is not supported by this graph-scoping path.

#### Advanced Configuration Options

The assembler supports additional properties for fine-grained HTTP configuration.
The assembler configuration follows the [RDFLinkHTTPBuilder API](https://github.com/apache/jena/blob/main/jena-rdfconnection/src/main/java/org/apache/jena/rdflink/RDFLinkHTTPBuilder.java):

```turtle
<#dataset> a dsg:DatasetHTTP ;
    dsg:destination "http://localhost:3030/dataset" ;

    # Accept headers for different query types
    dsg:acceptSelectQuery "application/sparql-results+json" ;
    dsg:acceptAskQuery "application/sparql-results+json" ;
    dsg:acceptGraph "text/turtle,application/n-triples" ;
    dsg:acceptDataset "application/trig,application/n-quads" ;
    dsg:acceptQuery "*/*" ;  # Fallback for unknown query types

    # Output formats
    dsg:quadsFormat "trig" ;
    dsg:triplesFormat "turtle" ;

    # Send modes (query and update)
    dsg:querySendMode "asPost" ;
    dsg:updateSendMode "asPostForm" ;

    # Parse checks
    dsg:parseCheckSPARQL true .
```

**Available properties:**

| Property | Description | Values |
|----------|-------------|--------|
| `dsg:acceptSelectQuery` | Accept header for SELECT queries | MIME type (e.g., `application/sparql-results+json`) |
| `dsg:acceptAskQuery` | Accept header for ASK queries | MIME type |
| `dsg:acceptGraph` | Accept header for CONSTRUCT/DESCRIBE (graphs) | MIME type |
| `dsg:acceptDataset` | Accept header for CONSTRUCT QUAD/datasets | MIME type |
| `dsg:acceptQuery` | Fallback Accept header for all queries | MIME type |
| `dsg:acceptQuery` | Fallback Accept header for all queries | MIME type |
| `dsg:quadsFormat` | Output format for quads | Language name (e.g., `trig`, `n-quads`) or MIME type |
| `dsg:triplesFormat` | Output format for triples | Language name (e.g., `turtle`, `n-triples`) or MIME type |
| `dsg:querySendMode` | How to send SPARQL queries | `asGetWithLimitForm`, `asGetWithLimitBody`, `asGetAlways`, `asPostForm`, `asPost` |
| `dsg:updateSendMode` | How to send SPARQL updates | `asPostForm`, `asPost` |
| `dsg:parseCheckSPARQL` | Validate SPARQL queries/updates | `true` or `false` |

#### HTTP User-Agent Configuration

**Technical limitation:** Due to Jena's HTTP architecture, the User-Agent header cannot be configured per-dataset via the assembler. The `HttpClient` in Java doesn't support default headers at the client level.

**Available options:**

1. **Global configuration** (applies to all HTTP clients in the JVM):
   ```java
   import org.apache.jena.http.HttpEnv;
   import java.net.http.HttpClient;
   
   HttpClient client = HttpClient.newBuilder()
       .header("User-Agent", "MyApp/1.0")
       .build();
   HttpEnv.setDftHttpClient(client);
   ```

2. **Per-dataset via assembler** (uses global registry):
   The assembler supports the `dsg:userAgent` property, which registers the User-Agent header globally for the configured endpoints:
   ```turtle
   <#dataset> a dsg:DatasetHTTP ;
       dsg:destination "http://localhost:3030/dataset" ;
       dsg:userAgent "MyApp/1.0" .
   ```
   **Warning:** This approach registers the header globally in a shared registry due to current technical limitations. If multiple datasets target the same URL with different User-Agents, they may interfere with each other. The assembler logs a warning the first time it encounters a `dsg:userAgent` property.

## Build

```bash
# Clone the repository
git clone https://github.com/Scaseco/jena-proxy.git
cd jena-proxy-parent

# Build with Maven
mvn clean install

# Run tests
mvn test
```

## Requirements

- Java 21 or later
- Maven 3.8+
- Apache Jena 6.2.0+

## License

This project is licensed under the [Apache License 2.0](LICENSE).

## Project Information

- **GitHub**: https://github.com/Scaseco/jena-proxy
- **Issues**: https://github.com/Scaseco/jena-proxy/issues
- **Maven Central**: https://search.maven.org/search?q=g:org.aksw.jena.proxy


## Federation feature summary

Federation planning is enabled automatically for `DatasetGraphMapLink` datasets
with named proxy graph views, including those assembled with `ja:RDFDataset` or
`dsg:DatasetProxy`.
Local and remote named graphs can coexist. See the [setup example](#proxy-graph-views-and-a-composite-dataset)
and [algebra rules](jena-proxy-dataset-sparql/README-algebra.md) for details.

| Feature | Behavior | Scope / limitations |
| --- | --- | --- |
| Graph routing | Delegates each eligible `GRAPH` pattern to its backing RDFLink endpoint. | Local graph aliases can select remote named graphs or endpoint default graphs. |
| Source selection | Constant graph names and graph-selection `VALUES` avoid requests to unselected services. | General joins remain local; correlated joins can issue requests per input binding. |
| Counts across graphs | Computes partial `COUNT(*)` / `COUNT(?var)` remotely and sums contributions locally. | Conservative non-DISTINCT count splitting is enabled by default. |
| Aggregation per service | Delegates complete eligible groups, including standard DISTINCT aggregates, SUM and AVG. | Does not split these aggregates across services by default. |
| DISTINCT | Places eligible duplicate elimination in branches and services, including union-default-graph scans. | Retains final duplicate elimination across branches and the final query projection. |
| ORDER BY | Sorts eligible branches remotely and merges sorted streams locally, including union-default-graph scans. | Variable/constant keys, ASC/DESC; endpoints must use compatible RDF term ordering. [Limits and spill fallback](jena-proxy-dataset-sparql/README-udg.md#sorting-and-projected-distinct). |
| Projection | Transfers only needed columns where possible, retaining hidden sort keys until after merging. | Preserves final client projection, DISTINCT semantics and result multiplicities. |
| LIMIT / OFFSET | Preserves the global slice, including ARQ's TopN form. | Does not introduce per-service limits or offsets. |
| Mixed local / remote graphs | Includes local branches in aggregation, duplicate elimination and ordered merging. | Local branches may still need local grouping or sorting. |
| Union default graph | Optional query rewriting makes named graphs accessible as a virtual default graph. | Enable with `dsg:unionDefaultGraph true`; strictness and cross-graph deduplication are configurable. |
| Execution settings | Copies the request context and preserves the configured optimizer. | No assembler opt-in required; `QueryEngineFactoryProxyGraphs.ENABLED = false` opts out. Non-empty initial bindings skip optional federation optimizations. |
| Request scheduling | Opens and reads service streams sequentially. | Parallel requests and batched correlated execution are not yet implemented. Blocked by the service enhancer PR which adds basic infrastructure. |

## Changelog

Newest versions first.

| Version | Highlights |
| --- | --- |
| [v0.8.3](https://github.com/jenax-plus/jena-proxy/releases/tag/v0.8.3) | Optimizes union-default-graph queries with projection, DISTINCT and ORDER BY pushdown, including remote sorting with local merging and hidden sort-key handling. |
| [v0.8.2](https://github.com/jenax-plus/jena-proxy/releases/tag/v0.8.2) | Improved and optimized union default graph support. |
| [v0.8.1](https://github.com/jenax-plus/jena-proxy/releases/tag/v0.8.1) | Fixed broken jena-proxy-bom. |
| [v0.8.0](https://github.com/jenax-plus/jena-proxy/releases/tag/v0.8.0) | Adds profile-free SPARQL federation over virtual graph datasets, including remote aggregation, DISTINCT placement and distribution, and ORDER BY pushdown with local sorted merging. |
| [v0.7.1](https://github.com/jenax-plus/jena-proxy/releases/tag/v0.7.1) | Adds compatibility with [jena-exectracker](https://github.com/jenax-plus/jena-exectracker). |
| [v0.7.0](https://github.com/jenax-plus/jena-proxy/releases/tag/v0.7.0) | Introduces the proxy architecture for transparent remote dataset access through SPARQL and RDFLink. |

