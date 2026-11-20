package com.ian.transit.period;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The BigQuery client, only when bigquery.enabled is true. It authenticates
 * with Application Default Credentials: the service account on Cloud Run,
 * the developer's gcloud login locally. Without it every period or hourly
 * request answers 503 and the typical day still works.
 */
@Configuration
@EnableConfigurationProperties(BigQueryProperties.class)
public class BigQueryConfig {

    @Bean
    @ConditionalOnProperty(prefix = "bigquery", name = "enabled", havingValue = "true")
    public BigQuery bigQuery(BigQueryProperties props) {
        if (props.project() == null || props.project().isBlank()) {
            throw new IllegalStateException("bigquery.enabled is true but bigquery.project is not set");
        }
        return BigQueryOptions.newBuilder()
                .setProjectId(props.project())
                .setLocation(props.location())
                .build()
                .getService();
    }
}
