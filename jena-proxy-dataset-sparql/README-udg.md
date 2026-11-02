# Union default graph rewrites

`org.aksw.jena.proxy.algebra.udg` is separate from federation planning. Its opt-in
`TransformUnionDefaultGraph` virtualizes the default graph as the union of the
input dataset's named graphs, with set semantics by default. The physical default
graph is excluded. The transform itself has no assembler or federation dependencies;
the RDFLink module offers optional context/assembler integration.

```java
Op rewritten = TransformUnionDefaultGraph.rewrite(Algebra.compile(query));
```

Apply to the complete query algebra before optimization and source selection.
The public API always installs graph-scope tracking; its worker is private.
Explicit `GRAPH ?g` and `GRAPH <iri>` patterns retain their scope, including inside
LATERAL and EXISTS/NOT EXISTS, so they remain usable for provenance lookups.
Existing SERVICE bodies are left intact because they use a separate dataset.

Default-graph BGPs are split into independent triple matches. Each match removes
cross-graph duplicates and hides its fresh graph variable before joining, so
joins can cross graphs and surrounding query multiplicities remain intact.
Ground triples become existence tests. Shared query blank-node variables get
local join aliases; data blank-node identities are preserved. Triple, quad-pattern
and quad-block input forms are supported. Empty patterns and named graph enumeration
retain their original semantics.

## Strictness

Strict mode is the default. Unsupported default-graph property paths, property
functions and procedures raise `UnsupportedOperationException`. Opaque algebra
extensions also raise an error because their hidden patterns cannot be inspected.
Paths inside explicit named graphs and SERVICE bodies retain ordinary execution.

For a deliberate best-effort rewrite, pass a context with strictness disabled:

```java
Context requestContext = dataset.getContext().copy();
requestContext.set(TransformUnionDefaultGraph.STRICT, false);
Op rewritten = TransformUnionDefaultGraph.rewrite(Algebra.compile(query), requestContext);
```

The context symbol is `https://w3id.org/aksw/jena/proxy#udgStrict`. An absent setting,
`true`, or the one-argument method selects strict mode; only explicit `false`
selects permissive mode. Each call reads the supplied context without changing it.
Callers choose and merge dataset/request settings before invoking the rewrite.

Permissive mode leaves unsupported operators in place and still rewrites eligible
triple patterns. An unchanged default-graph path runs against the physical default
graph, so it can miss paths spanning named graphs. No graph-local approximation
is substituted. This is algebra rewriting, not an active-graph wrapper: custom
scalar functions that access the graph themselves are not virtualized. Input
SPARQL dataset descriptions remain the caller's responsibility.

## Cross-graph deduplication

Deduplication defaults to true. To skip the per-triple DISTINCT operations:

```java
requestContext.set(TransformUnionDefaultGraph.DEDUPLICATE, false);
Op rewritten = TransformUnionDefaultGraph.rewrite(Algebra.compile(query), requestContext);
```

The context symbol is `https://w3id.org/aksw/jena/proxy#udgDeduplicate`. This setting
is independent of strictness. For disjoint graph contents, disabling deduplication
preserves results and allows scans to stream without retaining previously seen
bindings for duplicate elimination. Other operators in the query can still buffer.

If graphs overlap, false deliberately selects **bag semantics**, rather than a set
union: a triple found in two graphs contributes twice. Two matching triple patterns
with two graph occurrences each can yield four joined solutions. Ground triples
also retain one solution per matching graph. Query-level DISTINCT is still honored.

## Optional proxy assembler integration

The RDFLink module registers `dsg:DatasetProxy` (`dsg:` is
`https://w3id.org/aksw/jena/dataset#`). It uses Jena's normal graph-collection
assembler to create a plain DatasetGraphMapLink, then sets context options from
`dsg:unionDefaultGraph`, `dsg:udgStrict`, `dsg:udgDeduplicate` and
`dsg:proxyGraphsEnabled`. It introduces no dataset subtype or wrapper.

UDG mode defaults off. Enable with `dsg:unionDefaultGraph true` or set
`ProxyDatasetSettings.UNION_DEFAULT_GRAPH` on a dataset/request context. An ordinary
`ja:RDFDataset` can use `ja:context` with the same enable-symbol URI. Explicit
assembler properties override generic context entries, and request settings override
dataset defaults. The master proxy-engine opt-out also disables UDG handling.

The composite engine accepts a map-link dataset with UDG enabled even if every
graph is local. Existing proxy graph-view detection still reads the view's backing
DatasetGraphOverRDFLink. UDG rewriting runs before algebra graph-to-service routing,
independently of ARQ's optimizer switch, and the local plan remains algebra rather
than being serialized and reparsed. Existing graph-view and service-scope behavior
is preserved. Projection/DISTINCT boundaries introduced by UDG can keep some
aggregations local; enabling this mode does not guarantee every federation
optimization applies. See the main README for a complete query-only Fuseki configuration.

## Validation

`TestTransformUnionDefaultGraph` is dedicated to UDG behavior and uses local
in-memory datasets, independently of proxy routing and federation transforms.
Tests compare result bags with a physically merged default graph, including the
GRAPH/LATERAL provenance regression, cross-graph joins, overlapping triples,
VALUES duplicates, ground patterns, query/data blank nodes, EXISTS, OPTIONAL,
MINUS, subqueries, quad forms, service boundaries and strict/permissive settings.
Generated tests cover 60 partitioned datasets and six queries per dataset.
Representative queries also round-trip through `OpAsQuery` and SPARQL parsing;
anonymous-only projections and zero-column results (such as ground patterns in
bag mode) are checked as algebra. `OpAsQuery` serialization of internal anonymous
variables or zero-column projections is not generally portable SPARQL. Bag-mode
tests also cover per-graph multiplicity, incoming VALUES duplicates, join
multiplication, disjoint graphs, and independent defaults.
