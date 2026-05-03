package com.ian.transit.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Common REST client configuration.
 */
@Configuration
public class RestClientConfig {
    /**
     * RestTemplate is exposed as a bean so external clients can share the same HTTP client
     * configuration through dependency injection.
     */
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
