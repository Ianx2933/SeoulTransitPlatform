package com.ian.transit.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Client for Seoul bus stop master API
 */
@Component
@RequiredArgsConstructor
public class SeoulBusApiClient {

    private static final Logger log = LoggerFactory.getLogger(SeoulBusApiClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${seoul.bus.api.key:}")
    private String apiKey;

    @Value("${seoul.bus.api.url:https://t-data.seoul.go.kr/apig/apiman-gateway/tapi/BisTbisMsSttn/1.0}")
    private String apiUrl;

    /**
     * Get standard stop code by ARS code
     */
    @Cacheable(value = "arsStandardCode", key = "#arsCode")
    public String getStandardCodeByArs(String arsCode) {
        try {
            String url = apiUrl + "?apikey=" + apiKey + "&stId=" + arsCode;

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isBlank()) {
                return null;
            }

            JsonNode root = objectMapper.readTree(responseBody);

            if (root.isArray() && !root.isEmpty()) {
                JsonNode firstItem = root.get(0);
                JsonNode standardCodeNode = firstItem.get("sttnId");

                if (standardCodeNode != null && !standardCodeNode.isNull()) {
                    return standardCodeNode.asText();
                }
            }

            return null;

        } catch (Exception exception) {
            log.warn(
                    "Failed to fetch standard code / 표준코드 조회 실패: arsCode={}, reason={}",
                    arsCode,
                    exception.getMessage()
            );
            return null;
        }
    }
}