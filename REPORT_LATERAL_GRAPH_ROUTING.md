# Assessment: graph routing inside LATERAL

Investigated 2026-11-11 against jena-proxy 0.8.5 and Jena 6.2.0.

## Verdict

There is a promising proxy-side solution that does **not** require modifying Jena
or replacing its LATERAL executor: retain graph selection as a graph pattern in
correlated scopes, instead of materializing it into a graph-binding table.
The prototype passes the reported query shape with optimization both on and off.
The fix is now implemented in `TransformProxyGraphRouting`, with a single
scope-aware traversal and permanent regression tests. No endpoint deployment has
been made. The analysis below records the assessed alternatives and tradeoffs.

The original query works on a plain `DatasetGraphFactory.create()` dataset.
The failure appears after variable GRAPH routing introduces table assignments.
This is not a simple variable-renaming collision.

## Why the current representation fails

Each variable GRAPH is routed approximately as a union of:

```text
Join(Table(?g = alias), Service(routeForAlias, pattern))
```

The LATERAL injector overwrites table bindings with the incoming binding. For
outer `?g = :b`, a branch for :a can therefore acquire `?g = :b` while continuing
to query source :a. LIMIT can consume results from the first source before the
intended source is reached. This explains subjects from one source being reported
under several graph names. Disabling ARQ optimization does not fix it.

The rewritten branch effectively contains VALUES, even though the input query
does not. Jena's syntax rules reject overlapping assignments in a LATERAL RHS;
the injector relies on those rules and assumes conflicting table assignments
cannot occur. Proxy rewriting introduces that shape **after** syntax validation.

The resulting limitation is broader than federation: materializing an otherwise
equivalent graph pattern into a table is not generally compositional under this
syntax-dependent injection contract. An upstream compatibility-check patch is
possible and has been tested, but it broadens that contract; it should not be
presented as an uncontroversial fix to ordinary valid-query LATERAL behavior.

## Recommended representation

Within a LATERAL RHS, use the equivalent of this branch:

```sparql
{
  GRAPH ?g {}
  FILTER sameTerm(?g, <urn:alias:a>)
  SERVICE <urn:internal-route:a> { ?s ?p ?x . ?x <urn:country> ?c }
}
```

The graph selector stays local to the composite dataset. It matches a graph
binding rather than assigning a new value to an already-bound variable.
Jena's native graph-pattern injection then preserves correlation. The complete
multi-triple pattern remains delegated to the existing service route.

Implementation should be a **scope-aware graph-routing choice**, not a generic
post-transform that converts arbitrary user VALUES. Keep existing singleton-table
routing outside correlated scopes, and use graph-pattern selectors for variable
GRAPHs throughout LATERAL RHS scopes, including nested subqueries/LATERAL.
Generated selector patterns must stay local and must not be routed again.
No new execution operator, custom LATERAL iterator, per-source metadata or Jena
class shadow is needed. Normal literal user VALUES retain Jena's syntax rules.

The [production routing transform](jena-proxy-dataset-rdflink/src/main/java/org/aksw/jena/rdflink/dataset/TransformProxyGraphRouting.java)
tracks scope using traversal hooks, without recomputing RHS subtrees. It counts
operand visits so shared Op objects do not accidentally mark the LHS as correlated.

### Tradeoffs and implementation checks

- `GRAPH ?g {}` consults the map-link dataset's local graph names. It adds local
  selection work compared with a singleton table, but does not need remote triple
  scans for the selector. The prototype rejects dataset-find fallback.
- Keeping this choice inside correlated scopes preserves the existing table-based
  fast path elsewhere. Some aggregation/DISTINCT/projection rules inside a
  correlated scope may stop at the selector join; assess captured requests before
  promising equivalent pushdown performance. Multi-pattern SERVICE delegation
  itself remains intact in the prototype.
- Any later materialization/rewrite must preserve the correlated constraint. The
  graph-pattern representation avoids the problematic table in today's pipeline;
  it does not make unrestricted materialization safe under Jena's current contract.
- Permanent tests now cover RDFLink and HTTP, mixed local/remote and empty graphs,
  remote default-graph aliases, expression-contained graph patterns, initial
  bindings, shared Op operands, opaque services and quad-form routing. They compare
  result bags and per-input LIMIT/OFFSET with ordinary local execution. A request
  capture checks that a bound selection sends its pattern only to that endpoint.
  Existing tests cover iterator cancellation and closure; no executor changed here.

## Previously working queries

A separate six-query corpus checks unbound graph variables (including UNDEF),
LHS bindings unrelated to the graph variable, constant GRAPH patterns, ordinary
non-LATERAL routing, shared graph/triple variables and duplicate input rows.
It passes against both the original routing class from HEAD and the updated
class, each in a separate JVM, over RDFLink and HTTP with optimization off/on.
The permanent regression compares result bags with ordinary local execution.
This is bounded regression evidence, not a proof for arbitrary custom operators.

Quad-form GRAPH enumeration is also lowered from `OpDatasetNames` to equivalent
local `GRAPH ... {}` because the generic executor does not implement
`OpDatasetNames`. The quad regression executes the full routed plan.

## Alternatives

| Approach | Assessment |
| --- | --- |
| Graph-pattern selector plus alias FILTER in correlated scopes | Recommended; avoids conflicting assignments and passed the tested cases. |
| FILTER around the existing graph-binding VALUES | Passed the same tests and proxy reproduction. Fragile: the FILTER is redundant on the original table and may be removed by a later optimizer/materialization pass, restoring the bug. |
| Rename the VALUES column and BIND it back to `?g` | Merely renaming is insufficient: assigning back to an incoming variable recreates the LATERAL assignment issue. Requires a deliberate binding/compatibility design. |
| Leave correlated GRAPH patterns entirely unrouted | Possible correctness fallback, but can fall back to remote graph-find calls and lose whole-pattern delegation. |
| Replace LATERAL with an ordinary join | Not generally equivalent; a subquery LIMIT/OFFSET would become global rather than per input binding. |
| Explicit UNION of constant GRAPH subqueries | A query-level workaround for this fixed list of graphs, with a separate LIMIT per branch. Not a general correlated-query rewrite. |
| Add compatibility filtering in Jena's injector | Small tested patch, but changes the injector contract and needs upstream agreement. Not required for the recommended proxy solution. |
| Disable optimization | Does not fix the current routing representation. |

## Reproductions and results

[LateralGraphSelectorAssessment.java](upstream-jena/LateralGraphSelectorAssessment.java)
runs on **unmodified Jena only**, without proxy dependencies. It compares graph
expansion strategies over a plain in-memory dataset. Six queries are run with
optimization off and on: the reported shape, ORDER/LIMIT/OFFSET, a bound subject,
repeated LHS VALUES rows, a subquery hiding `?g`, and nested LATERAL. Result bags
are compared with the original unexpanded query.

| Selector | Matches original | Mismatches |
| --- | ---: | ---: |
| Existing unguarded binding table | 2 | 10 |
| Binding table plus alias FILTER | 12 | 0 |
| Graph pattern plus alias FILTER | 12 | 0 |

Each guarded representation also passed the actual proxy integration reproduction
with optimization off/on by compiling **only** an alternate routing class into
`/tmp` and running it in a separate JVM. Jena classes were unchanged for these
selector experiments. The permanent implementation has additionally passed the broader regression
matrix above, including HTTP.

The [permanent proxy regression tests](jena-proxy-dataset-rdflink/src/test/java/org/aksw/jena/rdflink/dataset/TestProxyGraphLateral.java)
now run in the normal test suite and replace the original failing prototype test. The separate [injector investigation](upstream-jena/README-lateral.md)
contains the Jena-only failure reproducer and optional upstream patch. See
JENA-LOCAL-06 in [JENA_ISSUES.md](JENA_ISSUES.md) for tracking.

Run the selector assessment with an ARQ runtime classpath:

```sh
javac -cp "$JENA_CP" -d /tmp upstream-jena/LateralGraphSelectorAssessment.java
java -cp "/tmp:$JENA_CP" LateralGraphSelectorAssessment
```
