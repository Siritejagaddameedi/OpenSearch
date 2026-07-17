/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dsl.types;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.test.rest.OpenSearchRestTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-query-type DSL integration test. One {@code resources/datasets/<type>/} folder per query type
 * (mapping.json + bulk.json + dsl/q1.json); each provisioned via {@link DatasetProvisioner} and run
 * via {@link DatasetQueryRunner} against the live sandbox server ({@code dsl-query-executor} →
 * Calcite → Substrait → DataFusion). Talks HTTP only — no plugins in the test JVM.
 *
 * <p>For each {@link DslQueryTypeCatalog} entry the observed outcome is asserted against the cataloged
 * expectation:
 * <ul>
 *   <li><b>SUPPORTED</b> — query executes (HTTP 200);</li>
 *   <li><b>CONVERSION_ERROR / UNSUPPORTED</b> — query rejected (non-2xx);</li>
 *   <li><b>NOT_PROVISIONABLE</b> — index cannot be created (geo/nested field types rejected by parquet
 *       at HTTP 400), so provisioning is expected to fail.</li>
 * </ul>
 * Green when the catalog matches reality; red only on drift. All entries are evaluated before
 * asserting, so one run reports every drift.
 *
 * <pre>
 *   ./gradlew :sandbox:qa:dsl-query-types:restTest \
 *     --tests "org.opensearch.dsl.types.DslQueryTypesIT" -PrestCluster=localhost:9200
 * </pre>
 */
public class DslQueryTypesIT extends OpenSearchRestTestCase {

    private static final Logger logger = LogManager.getLogger(DslQueryTypesIT.class);

    @Override
    protected boolean preserveClusterUponCompletion() {
        return true;
    }

    @Override
    protected boolean preserveIndicesUponCompletion() {
        return true;
    }

    private enum Observed {
        EXECUTED,     // provisioned + query returned HTTP 200
        REJECTED,     // provisioned, query returned non-2xx
        NOT_CREATED   // index could not be created (field type unsupported by parquet)
    }

    public void testDslQueryTypeOutcomes() {
        List<String> mismatches = new ArrayList<>();
        List<String> regressions = new ArrayList<>();
        List<String> nowWorking = new ArrayList<>();
        int matched = 0;
        List<DslQueryTypeCatalog.Entry> entries = DslQueryTypeCatalog.all();

        for (DslQueryTypeCatalog.Entry entry : entries) {
            Observed observed = evaluate(entry);
            boolean ok;

            switch (entry.outcome) {
                case SUPPORTED:
                    ok = observed == Observed.EXECUTED;
                    if (!ok && observed == Observed.REJECTED) {
                        regressions.add(entry.type);
                    }
                    break;
                case CONVERSION_ERROR:
                case UNSUPPORTED:
                    ok = observed == Observed.REJECTED;
                    if (!ok && observed == Observed.EXECUTED) {
                        nowWorking.add(entry.type + " (was " + entry.outcome + ")");
                    }
                    break;
                case NOT_PROVISIONABLE:
                default:
                    ok = observed == Observed.NOT_CREATED;
                    if (!ok) {
                        nowWorking.add(entry.type + " (was NOT_PROVISIONABLE; index created)");
                    }
                    break;
            }

            String line = String.format(
                Locale.ROOT,
                "%-22s %-11s expected=%-17s observed=%s",
                entry.type,
                entry.family,
                entry.outcome,
                observed
            );
            if (ok) {
                matched++;
                logger.info("[match]    {}", line);
            } else {
                logger.warn("[MISMATCH] {}", line);
                mismatches.add(line);
            }
        }

        logger.info(
            "DSL query-type outcomes: {}/{} matched; {} mismatches ({} regressions, {} now-working)",
            matched,
            entries.size(),
            mismatches.size(),
            regressions.size(),
            nowWorking.size()
        );
        if (regressions.isEmpty() == false) {
            logger.warn("Regressions (expected SUPPORTED but rejected): {}", regressions);
        }
        if (nowWorking.isEmpty() == false) {
            logger.warn("Query types that now work — update DslQueryTypeCatalog: {}", nowWorking);
        }

        assertTrue(
            "DSL query-type outcomes drifted from the catalog ("
                + mismatches.size()
                + "). Update DslQueryTypeCatalog to reality (or fix the engine):\n"
                + String.join("\n", mismatches),
            mismatches.isEmpty()
        );
    }

    /** Provision the entry's dataset and run its {@code dsl/} query; report what happened (never throws). */
    private Observed evaluate(DslQueryTypeCatalog.Entry entry) {
        if (entry.provisionable() == false) {
            // Expected to fail at index creation (geo/nested on parquet). A successful create is drift.
            try {
                DatasetProvisioner.provision(client(), entry.dataset);
                return Observed.EXECUTED;
            } catch (Exception e) {
                return Observed.NOT_CREATED;
            }
        }

        try {
            DatasetProvisioner.provision(client(), entry.dataset);
        } catch (Exception e) {
            logger.warn("Provisioning failed for [{}]: {}", entry.type, e.getMessage());
            return Observed.REJECTED;
        }

        List<String> failures = DatasetQueryRunner.runQueries(
            client(),
            entry.dataset,
            "dsl",
            "json",
            null, // auto-discover dsl/q*.json
            (client, dataset, queryBody) -> {
                Request request = new Request("POST", "/" + dataset.indexName + "/_search");
                request.setJsonEntity(queryBody);
                Response response = client.performRequest(request);
                assertEquals("DSL " + entry.type + ": expected HTTP 200", 200, response.getStatusLine().getStatusCode());
                return entityAsMap(response);
            }
        );
        return failures.isEmpty() ? Observed.EXECUTED : Observed.REJECTED;
    }
}
