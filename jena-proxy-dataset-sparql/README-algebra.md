# Reusable algebra rewrites (draft)

`org.aksw.jena.proxy.algebra` is an opt-in package with only Jena/JDK dependencies.
It is independent of proxy graph discovery, RDFLink dispatch and graph/quad form,
and is intended to be extractable into a standalone algebra module. Individual
transforms remain opt-in for other callers; the composite proxy engine now uses
these planning phases by default in its execution request context.

## Rules

- `TransformGroupUnion`: split a group over a bag union into branch-local partial
  groups, a final merging group and optional finalization. Supports `OpUnion` and
  `OpDisjunction`, flattens nested unions and preserves the original output
  variables. Any unsupported aggregate leaves the entire group alone. Group keys
  must currently be plain variables. Apply this once, not to a fixed point.
- `TransformGroupService`: move a complete group into a fixed, non-SILENT service.
  Explicit service and group predicates certify dispatch eligibility, endpoint
  capabilities, expression semantics and independent evaluation (no dependence
  on incoming bindings). DISTINCT aggregates need no decomposition here. The old
  service syntax element is discarded when replacing the algebra body.
- `TransformGroupTable`: normalize a fully bound singleton VALUES table joined
  directly below a group into constant extensions, provided its variables are
  absent from the other operand. This leaves ordinary VALUES-before-SERVICE
  selection elsewhere in the query intact.
- `AlgebraQueries.asQuery`: convert independent fragments including bare
  `OpGroup` nodes with explicit aggregate projections. Jena's standard converter
  expects compiled Project/Extend/Group structure; a bare group otherwise becomes
  SELECT * without aggregate expressions in the SPARQL text.
  `prepareForRemote` additionally restores scoped variables without collisions and
  gives allocated variables explicit wire aliases, returning the output mapping
  needed to restore received bindings.
- `TransformPullExtend`: consolidated, self-contained adaptation of the Jenax
  `TransformPullExtend` and `ProjectExtend` by Claus Stadler, extended with the
  group case. Retains union, join, lateral, filter, slice, projection and DISTINCT
  handling. Matching constant bindings can move out of unions; differing graph
  identifiers remain branch-local. Grouped constants can move above a group if
  no aggregate depends on them. Constants discarded by grouping are eliminated.
  Removing the last explicit grouping key adds a row-count witness to suppress
  synthetic rows on empty input. Join lifting is conservative: it moves only
  constants that do not participate in the other side's variables. Assignment
  dependencies, empty projections and n-ary disjunctions are preserved.

Original Jenax sources:

- [TransformPullExtend](https://github.com/Scaseco/jenax/blob/develop/jenax-arq-parent/jenax-arq-algebra/src/main/java/org/aksw/jena_sparql_api/algebra/transform/TransformPullExtend.java)
- [ProjectExtend](https://github.com/Scaseco/jenax/blob/develop/jenax-arq-parent/jenax-arq-algebra/src/main/java/org/aksw/jena_sparql_api/algebra/transform/ProjectExtend.java)

## DISTINCT planning

`TransformDistinctPlacement` is a relocated copy of
[Aklakan's Jena PR implementation](https://github.com/Aklakan/jena/blob/20260613-index-for-basic-distinct/jena-tdb2/src/main/java/org/apache/jena/tdb2/solver/skipscan/TransformDistinctPlacement.java).
The copy initially preserved the existing class body verbatim, with relocated
package and appended entry points. The correctness review subsequently changed
only its two stability checks to use `ExprSafety.isStableAndTransparent`, because
ARQ's `ExprLib.isStable` does not certify registry-backed extension functions.
The placement algorithm and other existing methods remain unchanged.

```java
Op placementOnly = TransformDistinctPlacement.rewrite(input);
Op distributed = TransformDistinctPlacement.rewrite(input, true);
```

The `distributeOverUnion` flag defaults to false. Enabling it runs placement,
`TransformDistinctUnion`, singleton branch-binding normalization, and a second
placement pass. The rules are still independent and can be used separately.
The proxy engine enables distribution by default.

- `TransformDistinctUnion` adds local DISTINCTs to binary/n-ary union members and
  retains the final DISTINCT for duplicates occurring in different members.
  If a projection lies between DISTINCT and UNION, each branch uses that same
  projection so that irrelevant columns do not prevent duplicate elimination.
  Distribution is idempotent and preserves empty branches and unbound variables.
- `TransformDistinctTable` normalizes independent singleton tables immediately
  below DISTINCT (including a projection) into branch constants. It reuses the
  appended `TransformGroupTable.normalizeSingletonJoin` helper, leaving ordinary
  VALUES-before-SERVICE selection intact.
- `TransformDistinctService` delegates DISTINCT and its projection only to
  caller-approved fixed, non-SILENT services. Zero-column projections remain
  local because they cannot be represented by a SPARQL SELECT projection.

Placement respects the original stability/dependency checks and operator
barriers. Distribution never introduces DISTINCT into queries that did not
request it. Existing SERVICE bodies remain opaque. The proxy's usual eligibility
and initial-binding safeguards apply; global duplicate elimination stays in the
plan across multiple endpoints.

## ORDER BY planning

`TransformOrderUnion` distributes ORDER over binary or n-ary bag unions, creating
`OpMergeSorted` with an ordered input for each branch. It also recognizes TopN
introduced by ARQ's standard optimizer and retains its limit as a global Slice.
Stable transparent filters and projections retaining all sort variables can expose
an underlying union. Volatile filters and other operators remain barriers.

`TransformOrderService` is a separate rule. It pushes branch ORDER into fixed,
non-SILENT services approved by the caller. The predicate certifies endpoint
ordering compatibility as well as eligibility. Independent fully bound singleton
VALUES joins (including ARQ's Sequence form) become local constant extensions;
constant graph aliases are omitted from remote sort keys and restored locally.
Other extensions, unsafe expressions, slices and grouping remain barriers to
moving ORDER across them. Existing SERVICE bodies remain opaque to traversal.

Install these rules **after** the standard optimizer, including scope renaming:

```java
Op planned = standardOptimizer.rewrite(input);
planned = Transformer.transformSkipService(new TransformOrderUnion(), planned);
planned = Transformer.transformSkipService(
    new TransformOrderService(service -> approved(service)), planned);
```

The proxy enables both rules for its internal service routes. The extension is
opaque to subsequent standard algebra transforms; its effectiveOp is the original
standard ORDER algebra for inspection and conservative execution fallback. A standalone
service push also uses a one-branch merge to retain the same fallback safeguard.

The merge opens and reads branches sequentially, keeps one head per branch, and
uses a heap for O(log k) selection with O(k) merge state. Local branch sorts may
still buffer their inputs. Equal-key rows are all retained, without prescribing
a tie order. Only variable/constant sort keys are currently eligible: computed,
query-local and extension-function expressions retain ordinary sorting. Endpoint
RDF term comparisons must agree with ARQ's key comparison (including datatype
and unbound handling); SPARQL leaves some term orders implementation-dependent.
No per-branch LIMIT/OFFSET is introduced. Global DISTINCT and slices retain their
position; DISTINCT below ORDER is a placement barrier, because no assumption is
made that arbitrary DISTINCT implementations preserve input order. Correlated or non-root execution uses the original sort with the
original input iterator. Closing, cancellation and branch failures clean up every
opened branch; no threads or parallel request scheduling are introduced.

Tests cover sorted results and outgoing ORDER BY over RDFLink and local HTTP,
mixed local/remote branches, ASC/DESC, graph aliases, TopN and global OFFSET/LIMIT,
duplicates, unbound cells, empty branches, 150 generated partition sets, policy
barriers, correlated inputs, early close, cancellation and read failures.

## Aggregate policy

`AggregateDecompositionRegistry.counts()` supports `COUNT(*)` and `COUNT(expr)`
where the expression is a variable or RDF constant. Counts are merged with SUM.
Class matching is exact: DISTINCT and custom aggregates do not accidentally
inherit the non-distinct rules. Registry entries can be replaced or extended;
a decomposition supplies partial aggregates, merge aggregates and an optional
final expression. Temporary state uses query-wide fresh variables and is
projected away before returning to the caller.

`withNumericReassociation()` additionally supports SUM and AVG. AVG uses partial
SUM/COUNT pairs, sums the contributions and divides at the end. It preserves
ARQ's empty-input and aggregate-error behavior, including all-unbound input.
This policy explicitly permits numerical reassociation: floating-point rounding,
overflow and type promotion can differ from the original evaluation. It should
only be selected when that tradeoff is acceptable or suitable numeric guarantees
are available. Expressions involving functions, arithmetic, EXISTS or query-local
state are currently declined, even with this policy.

The default policy does not split MIN/MAX, GROUP_CONCAT, SAMPLE, DISTINCT or
custom aggregates. Error, ordering and transport semantics require separate rules.
SERVICE delegation can still move such complete aggregates if its caller-supplied
policies certify them. SERVICE SILENT and variable service names are declined.

## Composition

After source selection has produced eligible service branches:

```java
Op planned = TransformGroupUnion.rewrite(input);
planned = Transformer.transformSkipService(new TransformGroupTable(), planned);
planned = TransformPullExtend.transform(planned);
planned = Transformer.transformSkipService(
    new TransformGroupService(
        service -> routes.containsKey(service.getService()),
        group -> endpointSupports(group)),
    planned);
```

`routes` and `endpointSupports` belong to the calling planner; this package does
not discover sources or assume that arbitrary services are SPARQL endpoints.
Reapply the pull-extend phase if several extension layers need lifting. Construct
each variable-allocating transform with the complete current algebra, not just a
subtree. Preserve the engine's scope-renaming phase and use
`transformSkipService` so local planning does not rewrite existing remote bodies.

The proxy engine installs this pipeline before its existing optimizer, restricted
to its own query-local service routes and standard aggregates over variables or
constants. Cross-partition splitting uses the conservative count policy. Complete
single-service groups can include DISTINCT, SUM, AVG and other standard aggregates
without splitting. Custom aggregates, query-local expressions and grouping
expressions retain local evaluation. Existing service bodies remain opaque.
Non-empty initial bindings retain the previous evaluation path. Disabling ARQ
optimization also bypasses the extra optimizer pipeline.

The engine copies the execution request context, decorates its configured
`RewriteFactory` (or the default optimizer factory), and leaves the dataset context
and global optimizer unchanged. `QueryEngineFactoryProxyGraphs.ENABLED` can be set
to false on the dataset or request context to make both engine accept overloads
decline. Its assembler context URI is
`https://w3id.org/aksw/jena/proxy#proxyGraphsEnabled`; request settings take precedence.

When sending a bare remote Group through a query protocol, use
`AlgebraQueries.prepareForRemote` before serializing and apply its output mapping
to returned bindings. The proxy dispatcher uses this helper, including for HTTP.

## Validation

`mvn -pl jena-proxy-dataset-sparql -am test`

Tests compare result multisets (including RDF datatypes and unbound variables),
check empty and overlapping partitions, weighted AVG, aggregate errors, variable
capture, conservative fallbacks, the consolidated pull-extend rules and query
serialization. A query-local SERVICE executor verifies that composing the rules
actually delivers grouped subplans to both endpoints without using HTTP.

DISTINCT tests separately verify placement versus distribution, projected keys,
empty branches, binary/n-ary unions, idempotence, volatile/filter/limit barriers,
service eligibility and preservation of input multiplicities. Proxy integration
tests inspect outgoing queries to verify projected remote DISTINCT over local
RDFLink and HTTP, including final cross-endpoint duplicate elimination.

## Correctness review

The review found and fixed the following inherited issues:

- Union factoring checked equal assignment expressions without requiring each
  variable to be pullable/visible in every branch. It could expose an assignment
  hidden by a branch projection. Re-adding every rejected candidate to every
  branch could also expose hidden variables. Both checks are now branch-specific.
- LATERAL factoring reapplied the RHS projection to the complete result, dropping
  LHS outputs. The resulting projection now retains LHS and RHS outputs, and
  constant lifting declines overlapping variables or operands with no pullable
  assignments.
- `ExprLib.isStable` is insufficient to certify arbitrary registered functions.
  DISTINCT placement could reduce a stateful function from two calls/results to
  one; moving extensions across filters could change which values survive.
  `ExprSafety` treats registry/dynamic functions and graph-pattern expressions
  conservatively and is shared by the placement and pull-extend rules. Slice,
  group and join lifting apply the same conservative contract where relevant.

The previously adapted join rule is intentionally more conservative than the
Jenax original: an expression unbound before a join can become bound after it.
For example, lifting `BIND(?x + 1 AS ?tag)` past a join introducing `?x=2` changes
an unbound `?tag` into `3`. The original unconditional hoist is not generally
valid. Constant compatibility alone is not a counterexample: ARQ can reject the
conflicting extension at runtime. The regression uses the actual dependency case.

`TestRewriteCorrectness` contains focused reproductions and 800 generated
finite algebra cases, each checked against unmodified execution for pull-extend,
distinct planning, and their composition. Results are compared as bags including
unbound cells and RDF terms. Extension-function expressions are freshly copied
per execution to avoid ARQ's cached function binding contaminating the comparison.
Filter-only EXISTS correlations also have dedicated passing tests; they were not
confirmed defects. This bounded review is not a formal equivalence proof for all
ARQ operators, custom functions or correlated execution environments.

Next priorities: explicit determinism/dependency contracts for custom functions;
then safe VALUES/filter propagation through service unions, preserving selection
and duplicate multiplicities; then plan diagnostics explaining applied and
rejected rules. Cost-based placement is useful once those correctness contracts
are established.
