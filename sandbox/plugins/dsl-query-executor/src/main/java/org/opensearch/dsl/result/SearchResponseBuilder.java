/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.result;

import org.apache.lucene.search.TotalHits;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.search.SearchResponseSections;
import org.opensearch.action.search.ShardSearchFailure;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.dsl.executor.QueryPlans;
import org.opensearch.search.SearchHit;
import org.opensearch.search.SearchHits;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link SearchResponse} from execution results.
 */
public class SearchResponseBuilder {

    private SearchResponseBuilder() {}

    /**
     * Builds a SearchResponse from the given results and timing.
     *
     * @param results execution results from the plan executor
     * @param convertTimeNanos time spent in DSL-to-RelNode conversion, in nanoseconds
     * @return a SearchResponse
     */
    // TODO: Analytics plugin should return execution metadata alongside Iterable<Object[]> rows:
    // - executionTimeNanos: query execution time
    // - totalDocCount: total matching documents for hits.total (the engine paginates, so the
    //   returned HITS rows are already limited by `size`; the true total is not yet available —
    //   hits.total is currently reported as the number of returned rows with an EQUAL_TO relation)
    // - terminatedEarly: whether execution was terminated early
    // - timedOut: whether execution timed out
    // - shardInfo: total/successful/skipped/failed shard counts (reported as a synthetic single
    //   successful shard until the engine provides real shard metadata)
    public static SearchResponse build(List<ExecutionResult> results, long convertTimeNanos) {
        long tookInMillis = convertTimeNanos / 1_000_000;

        // TODO: populate the AGGREGATION section from AGGREGATION-typed results. For now only the
        // HITS path is materialized; aggregation results are not yet mapped into InternalAggregations.
        SearchHits hits = buildHits(results);

        SearchResponseSections sections = new SearchResponseSections(hits, null, null, false, null, null, 0);
        // Synthetic single-shard accounting until the engine returns real shard metadata (see TODO above).
        int totalShards = 1;
        int successfulShards = 1;
        int skippedShards = 0;
        return new SearchResponse(
            sections,
            null,
            totalShards,
            successfulShards,
            skippedShards,
            tookInMillis,
            ShardSearchFailure.EMPTY_ARRAY,
            SearchResponse.Clusters.EMPTY
        );
    }

    /**
     * Materialize the HITS-typed result (if any) into {@link SearchHits}. Each result row becomes a
     * {@link SearchHit} whose {@code _source} is the row values keyed by the plan's field names.
     * Returns empty hits when there is no HITS plan (e.g. a {@code size:0} aggregation-only request).
     */
    private static SearchHits buildHits(List<ExecutionResult> results) {
        ExecutionResult hitsResult = null;
        for (ExecutionResult result : results) {
            if (result.getType() == QueryPlans.Type.HITS) {
                hitsResult = result;
                break;
            }
        }
        if (hitsResult == null) {
            return SearchHits.empty(true);
        }

        List<String> fieldNames = hitsResult.getFieldNames();
        List<SearchHit> hitList = new ArrayList<>();
        int docId = 0;
        for (Object[] row : hitsResult.getRows()) {
            hitList.add(toSearchHit(docId++, fieldNames, row));
        }

        SearchHit[] hitArray = hitList.toArray(new SearchHit[0]);
        // The engine paginates, so hitArray already reflects `size`; report the count as the total
        // until the engine supplies a real total doc count (see class TODO). Score is not computed
        // by the analytics engine, so max_score is left as NaN.
        TotalHits totalHits = new TotalHits(hitArray.length, TotalHits.Relation.EQUAL_TO);
        return new SearchHits(hitArray, totalHits, Float.NaN);
    }

    /** Build one {@link SearchHit} from a row, setting {@code _source} to fieldName -> value. */
    private static SearchHit toSearchHit(int docId, List<String> fieldNames, Object[] row) {
        SearchHit hit = new SearchHit(docId);
        Map<String, Object> source = new LinkedHashMap<>();
        for (int i = 0; i < fieldNames.size() && i < row.length; i++) {
            source.put(fieldNames.get(i), row[i]);
        }
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            builder.map(source);
            hit.sourceRef(BytesReference.bytes(builder));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to build _source for DSL search hit", e);
        }
        return hit;
    }
}
