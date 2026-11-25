package com.ian.transit.curatedod.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Seoul route master by snapshot date (bus_route_stop_snapshot, loaded by
 * pipelines/bus_route_stop_snapshot): which stops a route served then, in
 * order, with positions. Keeps only changes, so a route's snapshot in force on
 * a day is its latest one on or before that day. Empty until migration 04 is
 * applied and the master is loaded.
 */
@Repository
@RequiredArgsConstructor
public class RouteSnapshotRepository {

    /** The snapshot in force on :at, else the route's first one after it. */
    private static final String SNAPSHOT_DATE = """
            SELECT COALESCE(
                (SELECT max(stdr_de) FROM bus_route_stop_snapshot WHERE route_nm IN (:names) AND stdr_de <= :at),
                (SELECT min(stdr_de) FROM bus_route_stop_snapshot WHERE route_nm IN (:names) AND stdr_de > :at)
            ) AS d
            """;

    private static final String ROUTE_ROWS = """
            SELECT route_id, seq, ars, node_nm, lat, lng
            FROM bus_route_stop_snapshot
            WHERE route_nm IN (:names) AND stdr_de = :d
            ORDER BY route_id, seq
            """;

    /** Latest known position of each stop number, any route, any date. */
    private static final String POINTS = """
            SELECT DISTINCT ON (ars) ars, node_nm, lat, lng
            FROM bus_route_stop_snapshot
            WHERE ars IN (:ars)
            ORDER BY ars, stdr_de DESC
            """;

    /** Every kept snapshot of the routes: one per version (the first date it was seen that way). */
    private static final String VERSIONS = """
            SELECT stdr_de, route_id, seq, ars, node_nm
            FROM bus_route_stop_snapshot
            WHERE route_nm IN (:names)
            ORDER BY stdr_de, route_id, seq
            """;

    private static final String SEEN = """
            SELECT min(first_seen) AS first_seen, max(last_seen) AS last_seen,
                   (SELECT max(last_seen) FROM bus_route_stop_snapshot_seen) AS latest
            FROM bus_route_stop_snapshot_seen
            WHERE route_nm IN (:names)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public record VersionRow(LocalDate date, String routeId, int seq, String ars, String name) {
    }

    /** first and last snapshot the route was in; latest is the newest snapshot loaded at all. */
    public record Seen(LocalDate first, LocalDate last, LocalDate latest) {
    }

    public List<VersionRow> versions(Collection<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        try {
            return jdbc.query(VERSIONS, new MapSqlParameterSource("names", List.copyOf(names)), (rs, i) -> new VersionRow(
                    rs.getDate("stdr_de").toLocalDate(), rs.getString("route_id"), rs.getInt("seq"),
                    rs.getString("ars"), rs.getString("node_nm")));
        } catch (BadSqlGrammarException e) {
            return List.of();
        }
    }

    /** Null until migration 05 is applied or when the route is in no snapshot. */
    public Seen seen(Collection<String> names) {
        if (names.isEmpty()) {
            return null;
        }
        try {
            return jdbc.queryForObject(SEEN, new MapSqlParameterSource("names", List.copyOf(names)), (rs, i) -> {
                Date first = rs.getDate("first_seen");
                Date last = rs.getDate("last_seen");
                Date latest = rs.getDate("latest");
                return first == null ? null
                        : new Seen(first.toLocalDate(), last.toLocalDate(), latest == null ? null : latest.toLocalDate());
            });
        } catch (BadSqlGrammarException e) {
            return null;
        }
    }

    public record Row(String routeId, int seq, String ars, String name, double lat, double lng) {
    }

    public record Snapshot(LocalDate date, Map<String, List<Row>> byRouteId) {
    }

    public record Point(String ars, String name, double lat, double lng) {
    }

    /** Null when the route has no snapshot (or the table is not there yet). */
    public Snapshot routeAt(Collection<String> names, LocalDate at) {
        if (names.isEmpty() || at == null) {
            return null;
        }
        try {
            var params = new MapSqlParameterSource("names", List.copyOf(names)).addValue("at", Date.valueOf(at));
            Date d = jdbc.queryForObject(SNAPSHOT_DATE, params, Date.class);
            if (d == null) {
                return null;
            }
            Map<String, List<Row>> byRoute = new HashMap<>();
            jdbc.query(ROUTE_ROWS, params.addValue("d", d), rs -> {
                byRoute.computeIfAbsent(rs.getString("route_id"), k -> new ArrayList<>())
                        .add(new Row(rs.getString("route_id"), rs.getInt("seq"), rs.getString("ars"),
                                rs.getString("node_nm"), rs.getDouble("lat"), rs.getDouble("lng")));
            });
            return byRoute.isEmpty() ? null : new Snapshot(d.toLocalDate(), byRoute);
        } catch (BadSqlGrammarException e) {
            return null;
        }
    }

    public Map<String, Point> points(Collection<String> paddedArs) {
        Map<String, Point> out = new HashMap<>();
        if (paddedArs.isEmpty()) {
            return out;
        }
        try {
            jdbc.query(POINTS, new MapSqlParameterSource("ars", List.copyOf(paddedArs)), rs -> {
                out.put(rs.getString("ars"), new Point(rs.getString("ars"), rs.getString("node_nm"),
                        rs.getDouble("lat"), rs.getDouble("lng")));
            });
        } catch (BadSqlGrammarException e) {
            out.clear();
        }
        return out;
    }
}
