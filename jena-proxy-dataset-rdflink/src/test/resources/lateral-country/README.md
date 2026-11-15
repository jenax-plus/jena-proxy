# Country / BACI LATERAL regressions

`part1.trig` and `part2.trig` are copies of the supplied `extra-tests` data.

`graphql-default.rq`, `graphql-graph-of.rq` and `graphql-graph-iri.rq` are frozen
SPARQL outputs from graphql4sparql's `country-baci` rewrite tests. Keeping the
wire queries as fixtures avoids a build dependency in either direction.
`graph-of` records the unsupported argument spelling from the original example;
it enumerates `GRAPH ?x`. The supported spelling is `@graph(iri: ...)`.

`graphql-reported.rq` is the exact query from the later HTTP 400 report. It also
contains an importer self-loop (the same variable in subject/object position),
so it is expected to produce no BACI child rows in the fixture.

The proxy integration tests reparse outbound SPARQL and compare its results
against a plain materialized reference dataset, over direct RDFLink and HTTP,
with union default graph enabled and optimization both disabled and enabled.
The nested LATERAL test uses one observation to make unordered LIMIT selection
deterministic; it asserts child rows for the correct rewrites, so bag comparison
cannot pass solely because both executions omitted the children.
