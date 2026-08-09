package com.ian.transit.common.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Common REST client configuration.
 *
 * RestTemplate is exposed as a single shared bean so external clients
 * such as the Flask prediction service and Seoul bus master API use the same
 * timeout policy through dependency injection.
 *
 * Timeouts are explicit because the previous {@code new RestTemplate()} had
 * no connect/read timeout: a hung downstream service could pin a Tomcat worker
 * thread indefinitely.
 */
@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();

        // Bound connection wait time. (연결 대기 시간을 제한합니다.)
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);

        // Bound downstream response wait time. (하위 서비스 응답 대기 시간을 제한합니다.)
        requestFactory.setReadTimeout(READ_TIMEOUT);

        return new RestTemplate(requestFactory);
    }
}
