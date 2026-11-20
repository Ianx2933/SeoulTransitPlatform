package com.ian.transit.period.infrastructure;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.JobStatistics;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.QueryParameterValue;
import com.google.cloud.bigquery.TableResult;
import com.ian.transit.period.BigQueryProperties;
import com.ian.transit.period.PeriodUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Runs one parameterised standard-SQL query with the byte cap and timeout of
 * BigQueryProperties, and turns every failure into PeriodUnavailableException
 * (503). Values always go in as named parameters; only table names from the
 * configuration are put into the SQL text.
 *
 * The byte cap is checked against BigQuery's estimate, which for a clustered
 * table is the upper bound of the partitions read (a 12-month area query:
 * 1.77 GB), while the bytes billed after clustering are what is logged here.
 * So the cap is set from the upper bound, and the project's daily query quota
 * bounds the cost per day.
 */
@Component
public class BigQueryRunner {

    private static final Logger log = LoggerFactory.getLogger(BigQueryRunner.class);

    private final ObjectProvider<BigQuery> bigQuery;
    private final BigQueryProperties props;

    public BigQueryRunner(ObjectProvider<BigQuery> bigQuery, BigQueryProperties props) {
        this.bigQuery = bigQuery;
        this.props = props;
    }

    public BigQueryProperties props() {
        return props;
    }

    public <T> List<T> query(String what, String sql, Map<String, QueryParameterValue> params,
                             Function<FieldValueList, T> row) {
        BigQuery client = bigQuery.getIfAvailable();
        if (client == null) {
            throw new PeriodUnavailableException("Period and hourly queries are switched off (bigquery.enabled)");
        }
        QueryJobConfiguration.Builder config = QueryJobConfiguration.newBuilder(sql)
                .setUseLegacySql(false)
                .setMaximumBytesBilled(props.maxBytesBilled())
                .setJobTimeoutMs(props.timeout().toMillis())
                .setLabels(Map.of("app", "transit-api", "query", what.replace('_', '-')));
        params.forEach(config::addNamedParameter);
        long started = System.nanoTime();
        try {
            Job job = client.create(JobInfo.of(config.build())).waitFor();
            if (job == null) {
                throw new PeriodUnavailableException("Period query job disappeared");
            }
            if (job.getStatus() != null && job.getStatus().getError() != null) {
                log.warn("bigquery {} failed: {}", what, job.getStatus().getError().getMessage());
                throw new PeriodUnavailableException("Period query failed; the typical day is still available");
            }
            TableResult result = job.getQueryResults();
            List<T> out = new ArrayList<>();
            for (FieldValueList r : result.iterateAll()) {
                out.add(row.apply(r));
            }
            JobStatistics.QueryStatistics stats = job.getStatistics();
            Long billed = stats == null ? null : stats.getTotalBytesBilled();
            log.info("bigquery {}: {} rows in {} ms, {} MB billed", what, out.size(),
                    (System.nanoTime() - started) / 1_000_000, billed == null ? "?" : billed / 1_000_000);
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PeriodUnavailableException("Period query was interrupted", e);
        } catch (BigQueryException e) {
            log.warn("bigquery {} failed: {}", what, e.getMessage());
            throw new PeriodUnavailableException("Period query failed; the typical day is still available", e);
        }
    }

    public static QueryParameterValue strings(java.util.Collection<String> values) {
        return QueryParameterValue.array(values.toArray(new String[0]), String.class);
    }
}
