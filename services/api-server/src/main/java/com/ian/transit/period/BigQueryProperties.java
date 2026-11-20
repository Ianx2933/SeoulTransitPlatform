package com.ian.transit.period;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Where period and hourly queries go (application.yaml, prefix bigquery).
 * The tables are transit-bigquery's dbt marts; the API's service account has
 * BigQuery Job User on the project and Data Viewer on the dataset only.
 *
 * maxBytesBilled caps every query: one past it fails before it runs and costs
 * nothing. BigQuery checks it against its estimate, the upper bound of the
 * partitions read (clustering is not counted): a 12-month route query 0.94 GB,
 * a 12-month area query 1.77 GB (2026-11-20). The default 3 GB lets those run;
 * the bytes actually billed are logged (BigQueryRunner) and the daily query
 * quota of the BigQuery project bounds the cost.
 */
@ConfigurationProperties(prefix = "bigquery")
public record BigQueryProperties(
        boolean enabled,
        String project,
        String dataset,
        String location,
        long maxBytesBilled,
        Duration timeout
) {

    public BigQueryProperties {
        if (dataset == null || dataset.isBlank()) {
            dataset = "transit_marts";
        }
        if (location == null || location.isBlank()) {
            location = "asia-northeast3";
        }
        if (maxBytesBilled <= 0) {
            maxBytesBilled = 3_000_000_000L;
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(20);
        }
    }

    /** `project.dataset.table`, from configuration only (never from a request). */
    public String table(String name) {
        return "`" + project + "." + dataset + "." + name + "`";
    }
}
