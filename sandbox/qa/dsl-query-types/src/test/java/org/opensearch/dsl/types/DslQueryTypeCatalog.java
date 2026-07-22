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
 * <p>Each entry pairs a query-type folder (its {@link Dataset}: {@code mapping.json} + {@code bulk.json}
 * + {@code dsl/q1.json}) with the {@link Outcome} the {@code dsl-query-executor} path is expected to
 * produce against the live sandbox server. {@link DslQueryTypesIT} provisions each type's index via
 * {@link DatasetProvisioner} and runs its {@code dsl/} queries via {@link DatasetQueryRunner}, then
 * asserts observed == expected so drift (a type that starts/stops working) surfaces.
 *
 * <p>Outcomes are ground-truthed: only {@code term}, {@code terms}, {@code exists}, {@code match_all}
 * execute; supported types with an unsupported option/form give {@code conversion_exception};
 * {@code geo_*}/{@code nested} field mappings are rejected at index creation (NOT_PROVISIONABLE);
 * every other query type gives {@code runtime_exception}.
 */
public final class DslQueryTypeCatalog {

    private DslQueryTypeCatalog() {}

    /** Expected result of routing a query type through the DSL engine. */
    public enum Outcome {
        /** Registered translator: executes, HTTP 200 (stub result today). */
        SUPPORTED,
        /** Supported type + an option/form the translator rejects → {@code conversion_exception}. */
        CONVERSION_ERROR,
        /** No translator yet → {@code runtime_exception}. */
        UNSUPPORTED,
        /** Field mapping (geo/nested) cannot be created on the parquet backend → skipped at provisioning. */
        NOT_PROVISIONABLE
    }

    /** One query-type entry: its dataset folder/index, family, and expected outcome. */
    public static final class Entry {
        /** Query-type key == folder name under {@code resources/datasets/}. */
        public final String type;
        /** Query family (term-level, full-text, compound, scoring, span, relational, geo, specialized). */
        public final String family;
        /** Expected outcome against the DSL engine. */
        public final Outcome outcome;
        /** Dataset descriptor: folder name == index name == {@link #type}. */
        public final Dataset dataset;

        Entry(String type, String family, Outcome outcome) {
            this.type = type;
            this.family = family;
            this.outcome = outcome;
            this.dataset = new Dataset(type, type);
        }

        /** Whether this type's field mapping can be provisioned on the parquet backend. */
        public boolean provisionable() {
            return outcome != Outcome.NOT_PROVISIONABLE;
        }
    }

    private static Entry e(String type, String family, Outcome outcome) {
        return new Entry(type, family, outcome);
    }

    /** All catalogued query types, aligned 1:1 with the {@code resources/datasets/<type>/} folders. */
    public static List<Entry> all() {
        return List.of(
            // ── SUPPORTED ──
            e("match_all", "specialized", Outcome.SUPPORTED),
            e("no_query", "specialized", Outcome.SUPPORTED),
            e("term", "term-level", Outcome.SUPPORTED),
            e("terms", "term-level", Outcome.SUPPORTED),
            e("exists", "term-level", Outcome.SUPPORTED),

            // ── CONVERSION_ERROR ──
            e("terms_boost", "term-level", Outcome.CONVERSION_ERROR),
            e("terms_name", "term-level", Outcome.CONVERSION_ERROR),
            e("terms_value_type", "term-level", Outcome.CONVERSION_ERROR),
            e("exists_boost", "term-level", Outcome.CONVERSION_ERROR),
            e("terms_lookup", "term-level", Outcome.CONVERSION_ERROR),

            // ── UNSUPPORTED: term-level ──
            e("match_none", "specialized", Outcome.UNSUPPORTED),
            e("terms_set", "term-level", Outcome.UNSUPPORTED),
            e("range", "term-level", Outcome.UNSUPPORTED),
            e("range_on_date", "term-level", Outcome.UNSUPPORTED),
            e("range_on_ip", "term-level", Outcome.UNSUPPORTED),
            e("prefix", "term-level", Outcome.UNSUPPORTED),
            e("wildcard", "term-level", Outcome.UNSUPPORTED),
            e("regexp", "term-level", Outcome.UNSUPPORTED),
            e("fuzzy", "term-level", Outcome.UNSUPPORTED),
            e("ids", "term-level", Outcome.UNSUPPORTED),

            // ── UNSUPPORTED: full-text ──
            e("match", "full-text", Outcome.UNSUPPORTED),
            e("match_phrase", "full-text", Outcome.UNSUPPORTED),
            e("match_phrase_prefix", "full-text", Outcome.UNSUPPORTED),
            e("match_bool_prefix", "full-text", Outcome.UNSUPPORTED),
            e("multi_match", "full-text", Outcome.UNSUPPORTED),
            e("combined_fields", "full-text", Outcome.UNSUPPORTED),
            e("query_string", "full-text", Outcome.UNSUPPORTED),
            e("simple_query_string", "full-text", Outcome.UNSUPPORTED),
            e("common", "full-text", Outcome.UNSUPPORTED),
            e("intervals", "full-text", Outcome.UNSUPPORTED),

            // ── UNSUPPORTED: compound ──
            e("bool", "compound", Outcome.UNSUPPORTED),
            e("constant_score", "compound", Outcome.UNSUPPORTED),
            e("boosting", "compound", Outcome.UNSUPPORTED),
            e("dis_max", "compound", Outcome.UNSUPPORTED),

            // ── UNSUPPORTED: scoring / scripting ──
            e("function_score", "scoring", Outcome.UNSUPPORTED),
            e("script_score", "scoring", Outcome.UNSUPPORTED),
            e("script", "scoring", Outcome.UNSUPPORTED),
            e("distance_feature", "scoring", Outcome.UNSUPPORTED),

            // ── UNSUPPORTED: specialized ──
            e("more_like_this", "specialized", Outcome.UNSUPPORTED),
            e("wrapper", "specialized", Outcome.UNSUPPORTED),

            // ── UNSUPPORTED: span ──
            e("span_term", "span", Outcome.UNSUPPORTED),
            e("span_near", "span", Outcome.UNSUPPORTED),
            e("span_or", "span", Outcome.UNSUPPORTED),
            e("span_not", "span", Outcome.UNSUPPORTED),
            e("span_first", "span", Outcome.UNSUPPORTED),
            e("span_multi", "span", Outcome.UNSUPPORTED),
            e("field_masking_span", "span", Outcome.UNSUPPORTED),
            e("span_containing", "span", Outcome.UNSUPPORTED),
            e("span_within", "span", Outcome.UNSUPPORTED),

            // ── NOT_PROVISIONABLE: relational (parquet rejects nested fields) ──
            e("nested", "relational", Outcome.NOT_PROVISIONABLE),

            // ── NOT_PROVISIONABLE: geo (parquet rejects geo_point/geo_shape) ──
            e("geo_bounding_box", "geo", Outcome.NOT_PROVISIONABLE),
            e("geo_distance", "geo", Outcome.NOT_PROVISIONABLE),
            e("geo_polygon", "geo", Outcome.NOT_PROVISIONABLE),
            e("geo_shape", "geo", Outcome.NOT_PROVISIONABLE),
            e("geo_intersection", "geo", Outcome.NOT_PROVISIONABLE),
            e("geo_within", "geo", Outcome.NOT_PROVISIONABLE),
            e("geo_disjoint", "geo", Outcome.NOT_PROVISIONABLE)
        );
    }
}
