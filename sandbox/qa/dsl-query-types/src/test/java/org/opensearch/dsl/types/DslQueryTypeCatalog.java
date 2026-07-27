/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.types;

import java.util.List;

/**
 * Catalog of DSL query types, one entry per {@code resources/datasets/<type>/} folder.
 *
 * <p>Each entry names a query-type folder (its {@link Dataset}: {@code mapping.json} + {@code bulk.json}
 * + {@code dsl/q{N}.json}). {@link DslQueryTypesIT} turns every {@code (entry, queryNumber)} pair into
 * an <b>independent</b> parameterized test that provisions the type's parquet index, runs the query,
 * and validates the response against a golden file ({@code dsl/expected/q{N}.json}).
 *
 * <p>The suite's purpose is to map what the parquet/composite engine can and cannot serve. Every type
 * is treated as <b>expected-to-work</b>: a type is green only when its live response matches its golden.
 * Types the engine cannot handle today — geo/nested field mappings (rejected at index creation),
 * multi-valued keyword arrays (rejected at ingest), custom {@code _id}s (rejected by
 * {@code append_only}) — therefore surface as <b>red</b>, which is the finding we want to expose, not
 * hide. There is no per-type "expected outcome" guesswork: the golden is the single source of truth.
 */
public final class DslQueryTypeCatalog {

    private DslQueryTypeCatalog() {}

    /** One query-type entry: its dataset folder/index and query family. */
    public static final class Entry {
        /** Query-type key == folder name under {@code resources/datasets/}. */
        public final String type;
        /** Query family (term-level, full-text, compound, scoring, span, relational, geo, specialized). */
        public final String family;
        /** Dataset descriptor: folder name == index name == {@link #type}. */
        public final Dataset dataset;

        Entry(String type, String family) {
            this.type = type;
            this.family = family;
            this.dataset = new Dataset(type, type);
        }
    }

    private static Entry e(String type, String family) {
        return new Entry(type, family);
    }

    /** All catalogued query types, aligned 1:1 with the {@code resources/datasets/<type>/} folders. */
    public static List<Entry> all() {
        return List.of(
            // ── specialized ──
            e("match_all", "specialized"),
            e("no_query", "specialized"),
            e("match_none", "specialized"),
            e("more_like_this", "specialized"),
            e("wrapper", "specialized"),

            // ── term-level ──
            e("term", "term-level"),
            e("terms", "term-level"),
            e("exists", "term-level"),
            e("terms_boost", "term-level"),
            e("terms_name", "term-level"),
            e("terms_value_type", "term-level"),
            e("exists_boost", "term-level"),
            e("terms_lookup", "term-level"),
            e("terms_set", "term-level"),
            e("range", "term-level"),
            e("range_on_date", "term-level"),
            e("range_on_ip", "term-level"),
            e("prefix", "term-level"),
            e("wildcard", "term-level"),
            e("regexp", "term-level"),
            e("fuzzy", "term-level"),
            e("ids", "term-level"),

            // ── keyword-array shapes (probe multi-valued keyword support) ──
            e("tags_scalar", "term-level"),
            e("tags_single_elem", "term-level"),
            e("tags_multi", "term-level"),

            // ── full-text ──
            e("match", "full-text"),
            e("match_phrase", "full-text"),
            e("match_phrase_prefix", "full-text"),
            e("match_bool_prefix", "full-text"),
            e("multi_match", "full-text"),
            e("combined_fields", "full-text"),
            e("query_string", "full-text"),
            e("simple_query_string", "full-text"),
            e("common", "full-text"),
            e("intervals", "full-text"),

            // ── compound ──
            e("bool", "compound"),
            e("constant_score", "compound"),
            e("boosting", "compound"),
            e("dis_max", "compound"),

            // ── scoring / scripting ──
            e("function_score", "scoring"),
            e("script_score", "scoring"),
            e("script", "scoring"),
            e("distance_feature", "scoring"),

            // ── span ──
            e("span_term", "span"),
            e("span_near", "span"),
            e("span_or", "span"),
            e("span_not", "span"),
            e("span_first", "span"),
            e("span_multi", "span"),
            e("field_masking_span", "span"),
            e("span_containing", "span"),
            e("span_within", "span"),

            // ── relational (parquet rejects nested fields → red) ──
            e("nested", "relational"),

            // ── geo (parquet rejects geo_point/geo_shape → red) ──
            e("geo_bounding_box", "geo"),
            e("geo_distance", "geo"),
            e("geo_polygon", "geo"),
            e("geo_shape", "geo"),
            e("geo_intersection", "geo"),
            e("geo_within", "geo"),
            e("geo_disjoint", "geo")
        );
    }
}
