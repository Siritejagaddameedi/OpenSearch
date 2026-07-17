# Plan: implement `SearchResponseBuilder.build()` to return real DSL results

**Status:** PLAN ONLY — no code written yet. For review before implementation.

## Problem (verified against the running sandbox)

The full DSL pipeline works and computes correct rows — the server log for a
`term(city=seattle)` query shows:

```
DslQueryPlanExecutor  Query result rowCount=3
row[0]=[30, seattle, This is seattle city, alice, 95.5]
row[1]=[35, seattle, This is seattle city, carol, 92.3]
row[2]=[32, seattle, This is seattle city, eve, 91.0]
```

…but the same query over HTTP returns `hits.total=0`, `hits=[]`, `_shards={0,0,0,0}`.
The rows are discarded at the final step:

`sandbox/plugins/dsl-query-executor/.../result/SearchResponseBuilder.java:38-44`
```java
public static SearchResponse build(List<ExecutionResult> results, long convertTimeNanos) {
    SearchHits hits = SearchHits.empty(true);                 // <-- ignores `results`
    SearchResponseSections sections = new SearchResponseSections(hits, null, null, false, null, null, 0);
    return new SearchResponse(sections, null, 0, 0, 0, tookInMillis, ShardSearchFailure.EMPTY_ARRAY, SearchResponse.Clusters.EMPTY);
}
```

The data needed is already in hand:
- `ExecutionResult.getRows()` → `Iterable<Object[]>` (real values)
- `ExecutionResult.getFieldNames()` → `List<String>` (column names from the plan row type)
- `ExecutionResult.getType()` → `QueryPlans.Type` (`HITS` or `AGGREGATION`)

## Scope of this change

- **Production code**, in the `dsl-query-executor` plugin (not a test change).
- **In scope:** real `hits.total`, real `SearchHit[]` with `_source`, honor `size`/`from`,
  and route AGGREGATION results into the aggregations section.
- **Out of scope (no source on this backend — confirmed):** `_score` / `max_score`
  (analytics engine is columnar, computes no relevance score) and Lucene
  `getExplanation()`. These stay 0 / null and are documented as such.
- **Partial:** real `_shards` counts require engine metadata that
  `QueryPlanExecutor` does not return yet (see the class TODO). Interim: report a
  synthetic single successful shard, clearly commented, until the engine carries
  `shardInfo`.

## Design

### 1. HITS path — `ExecutionResult(type=HITS)` → `SearchHits`

For the (at most one) HITS result:
1. Collect rows into a `List<Object[]>`; `total = rows.size()`.
2. For each row build a `SearchHit`:
   - `new SearchHit(docId, /*id*/ null, emptyMap(), emptyMap())` — docId can be the
     row index (no real Lucene docId on this path).
   - Build `_source` as a `Map<String,Object>` by zipping `getFieldNames()` with the
     row's `Object[]` values, then `hit.sourceRef(BytesReference.bytes(xContentBuilder))`
     via `XContentFactory.jsonBuilder().map(sourceMap)`.
   - Leave score `NaN`/0 (no scoring). Do not set explanation.
3. `new SearchHits(hitArray, new TotalHits(total, TotalHits.Relation.EQUAL_TO), Float.NaN)`.

**`size`/`from` — RESOLVED by testing.** The engine paginates itself: a `size:2`
`match_all` produces the plan `LogicalSort(fetch=[2])` and the executor returns
`rowCount=2` (not 5). So:
- The builder does **NOT** slice — it turns exactly the returned rows into `SearchHit`s.
- **`hits.total` therefore CANNOT be `rows.size()`** (that would report 2 for a 5-doc
  match). A correct `hits.total` needs a separate total count the engine does not
  return today — this is precisely the `totalDocCount` in the class TODO
  (`SearchResponseBuilder.java:32-37`). **Interim options (decision needed):**
  (a) set `hits.total` = returned `rows.size()` with `Relation.GTE` and a comment
  (honest but imprecise when `size` < matches); or
  (b) issue/enable a companion COUNT plan so `hits.total` is exact
  (more work; aligns with the engine-metadata TODO). Recommend (b) for real parity,
  (a) as a first honest cut.

### 2. AGGREGATION path — `ExecutionResult(type=AGGREGATION)` → `InternalAggregations`

Reuse the existing translator machinery (already present in the plugin):
- `aggregation/bucket/BucketTranslator` + `TermsBucketTranslator.toBucketAggregation(agg, Iterable<BucketEntry>)`
  already produce an `InternalAggregation` from `BucketEntry` rows.
- `result/BucketEntry` is the row-shape for buckets.
- The builder (or a helper) maps agg-result `Object[]` rows → `BucketEntry` →
  `InternalAggregation`, collects into `InternalAggregations`, and passes it as the
  `aggregations` arg of `SearchResponseSections` (currently `null`).
- **Confirmed feasible by testing:** `size:0 + terms(city)` produces the plan
  `LogicalAggregate(group=[{1}], _count=[COUNT()])` and the executor returns
  `rowCount=2` — i.e. real bucket rows `[city, count]` arrive for mapping.
- **Note the existing metric translators** (`AvgMetricTranslator`, etc.) — metric-only
  aggs (`size:0` + `avg`) need their single-row result mapped to the metric
  `InternalAggregation`. Confirm the translator API covers metric (not just bucket)
  result construction; if not, that is additional work to flag.

### 3. `SearchResponseSections` + `SearchResponse`

```
new SearchResponseSections(hits, aggregations /*or null*/, null /*suggest*/,
    false /*timedOut*/, null /*terminatedEarly*/, null /*profile*/, numReducePhases);
new SearchResponse(sections, null /*scrollId*/,
    totalShards, successfulShards, skippedShards, tookInMillis,
    ShardSearchFailure.EMPTY_ARRAY, SearchResponse.Clusters.EMPTY);
```
- `tookInMillis`: keep current `convertTimeNanos/1e6` (add engine exec time once the
  engine returns it — TODO already tracked).
- shards: interim `total=successful=1, skipped=failed=0` (commented as synthetic).

### 4. Signature / caller

`build(List<ExecutionResult>, long convertTimeNanos)` may need the request's
`size`/`from` (and index name for hit `_index`). `TransportDslExecuteAction` has
`request.source()` in scope (line 87) — pass `size`/`from`/index through, or pass the
`SearchSourceBuilder`. Small, local caller change.

## Assertions this unlocks in the new tests (the point)

| Assertion | After this change |
|---|---|
| Exact hit count (`hits.total`) | ✅ real |
| Field values / `_source` | ✅ real (zip fieldNames+row) |
| Ordering (sort clauses) | ✅ rows arrive in engine/plan order |
| Aggregation buckets/metrics | ✅ once agg path mapped |
| Shard success/fail | ⚠️ synthetic until engine carries shardInfo |
| `_score` / `max_score` | ❌ out of scope (no scoring in engine) |
| `getExplanation()` | ❌ out of scope (no scoring in engine) |

## Risks / open items

1. **`from`/`size` semantics** — who paginates (engine vs builder)? Verify via log
   before implementing so `hits.total` stays correct.
2. **Metric aggregations** — confirm the existing translators build metric (not just
   bucket) `InternalAggregation`s from result rows; if not, extra work.
3. **Type mapping** — `Object[]` values are engine-native types (e.g. the log shows
   `95.5`, `30`); confirm they serialize into `_source` as the right JSON types for the
   mapped field (double/int/keyword/text). Lossy types (date_nanos, ip, scaled_float)
   may need conversion — out of scope for first cut, flag.
4. **`@AwaitsFix` on `DslQueryIT`** — once this lands, that E2E test should be
   un-muted and asserted for real (separate follow-up).
5. **Multiple HITS/AGG plans** — `QueryPlans` can hold both (size>0 + aggs); builder
   must populate both sections from the respective `ExecutionResult`s.

## Verification plan (after implementation)

- `term(city=seattle)` → assert `hits.total.value==3`, the 3 `_source`s == alice/carol/eve.
- `match_all` sorted by `age` asc → assert ordering.
- `size:0` + `terms(city)` → assert 2 buckets (seattle=3, portland=2).
- Then upgrade the new `dsl-query-types` tests from "HTTP 200" to real count/value/order
  assertions for the SUPPORTED query types.
