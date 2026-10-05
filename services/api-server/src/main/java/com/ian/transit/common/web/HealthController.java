package com.ian.transit.common.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness probe for Cloud Run.
 *
 * Deliberately checks nothing: it answers "this process is up and serving"
 * and nothing more. /actuator/health aggregates the datasource, Redis and
 * disk, so a brief dependency blip turns it DOWN — useful as a readiness
 * signal, wrong as a liveness signal, because Cloud Run would kill and
 * restart an instance that is actually fine.
 *
 * Use this path for the container health check and /actuator/health when
 * you want the dependency picture.
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public ResponseEntity<Map<String, String>> healthz() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
