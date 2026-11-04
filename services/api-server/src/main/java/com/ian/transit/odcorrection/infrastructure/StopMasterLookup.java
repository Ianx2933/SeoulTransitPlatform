package com.ian.transit.odcorrection.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Resolves a stop's standard code (노드id) from its ARS number using the local
 * Seoul stop master, bus_stop_location.
 *
 * This replaces the T-data BisTbisMsSttn API. That API ignores every filter and
 * returns the first 1,000 stops of the whole master, and the old client took the
 * first element as the answer, so every lookup that succeeded returned the same
 * Incheon stop (164000626). Checked against the 2026-08 boarding data, 노드id
 * equals the source's standard stop ID for 10,685 of 10,686 stops, so the local
 * table is the better source as well as the safer one.
 *
 * An ARS that maps to more than one node is ambiguous and returns empty rather
 * than a guess. Stops outside the Seoul master (Gyeonggi stops on cross-boundary
 * routes, the virtual terminus '~') also return empty.
 */
@Repository
@RequiredArgsConstructor
public class StopMasterLookup {

    private static final String SQL =
            "SELECT DISTINCT \"노드id\" FROM bus_stop_location WHERE \"정류장번호\" = ?";

    private final JdbcTemplate jdbcTemplate;

    public Optional<String> findStandardCodeByArs(String arsCode) {
        if (arsCode == null || arsCode.isBlank()) {
            return Optional.empty();
        }
        List<String> nodes = jdbcTemplate.queryForList(SQL, String.class, arsCode.trim());
        if (nodes.size() != 1 || nodes.get(0) == null || nodes.get(0).isBlank()) {
            return Optional.empty();
        }
        return Optional.of(nodes.get(0));
    }
}
