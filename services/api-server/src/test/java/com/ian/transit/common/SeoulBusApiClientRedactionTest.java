package com.ian.transit.common.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The bus master API takes its key as a query parameter, and RestTemplate copies
 * the full URL into I/O error messages. These tests lock down that the logged
 * reason never contains the key.
 */
class SeoulBusApiClientRedactionTest {

    private static final String KEY = "abc123SECRETKEY987";

    @Test
    void keyInResourceAccessMessageIsRedacted() {
        String message = "I/O error on GET request for \"https://t-data.seoul.go.kr/apig/apiman-gateway/"
                + "tapi/BisTbisMsSttn/1.0?apikey=" + KEY + "&stId=01001\": Read timed out";

        String redacted = SeoulBusApiClient.redactKey(message, KEY);

        assertFalse(redacted.contains(KEY));
        assertTrue(redacted.contains("apikey=***"));
    }

    @Test
    void messageWithoutKeyIsUnchanged() {
        assertEquals("404 Not Found", SeoulBusApiClient.redactKey("404 Not Found", KEY));
    }

    @Test
    void nullOrEmptyInputsAreHandled() {
        assertNull(SeoulBusApiClient.redactKey(null, KEY));
        assertEquals("text", SeoulBusApiClient.redactKey("text", ""));
        assertEquals("text", SeoulBusApiClient.redactKey("text", null));
    }
}
