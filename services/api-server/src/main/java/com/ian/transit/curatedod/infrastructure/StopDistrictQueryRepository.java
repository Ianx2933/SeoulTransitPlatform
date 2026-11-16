package com.ian.transit.curatedod.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the administrative district (행정동) of bus stops by ARS number, with
 * the same rules as the district demand queries: the stop point is
 * COALESCE(geom, point from lat/lng) and a district holds it when its
 * boundary ST_Covers the point. bus_stop_location is the primary source;
 * integrated_bus_stop_location fills in stops it does not have.
 *
 * Only the given stops (one route, usually under 200) and the districts they
 * fall in are read, so the query is light.
 */
@Repository
@RequiredArgsConstructor
public class StopDistrictQueryRepository {

    /** Boundary simplification for the map, in degrees (about 20 m). */
    private static final double SIMPLIFY_TOLERANCE = 0.0002;

    private static final String STOP_SQL = """
            WITH primary_stops AS (
                SELECT DISTINCT ON (LPAD("정류장번호"::text, 5, '0'))
                    LPAD("정류장번호"::text, 5, '0') AS ars,
                    COALESCE(geom, ST_SetSRID(ST_MakePoint("경도", "위도"), 4326)) AS point_geom
                FROM bus_stop_location
                WHERE LPAD("정류장번호"::text, 5, '0') IN (:ars)
                ORDER BY LPAD("정류장번호"::text, 5, '0'), "정류장명"
            ),
            outer_stops AS (
                SELECT DISTINCT ON (LPAD(ars_id::text, 5, '0'))
                    LPAD(ars_id::text, 5, '0') AS ars,
                    COALESCE(geom, ST_SetSRID(ST_MakePoint(lng, lat), 4326)) AS point_geom
                FROM integrated_bus_stop_location
                WHERE LPAD(ars_id::text, 5, '0') IN (:ars)
                  AND LPAD(ars_id::text, 5, '0') NOT IN (SELECT ars FROM primary_stops)
                ORDER BY LPAD(ars_id::text, 5, '0'), canonical_node_id
            ),
            stops AS (
                SELECT ars, point_geom FROM primary_stops
                UNION ALL
                SELECT ars, point_geom FROM outer_stops
            )
            SELECT s.ars, d.adm_cd, d.adm_nm
            FROM stops s
            JOIN LATERAL (
                SELECT adm_cd, adm_nm
                FROM public.admin_dong_boundary a
                WHERE s.point_geom IS NOT NULL AND ST_Covers(a.geom, s.point_geom)
                ORDER BY adm_cd
                LIMIT 1
            ) d ON true
            """;

    private static final String BOUNDARY_SQL = """
            SELECT adm_cd,
                   ST_AsGeoJSON(ST_SimplifyPreserveTopology(geom, :tolerance), 6) AS geometry
            FROM public.admin_dong_boundary
            WHERE adm_cd IN (:codes)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** A stop's district code and name. */
    public record StopDistrict(String code, String name) {
    }

    /** District of each padded ARS number; stops outside every boundary are absent. */
    public Map<String, StopDistrict> districtsOf(Collection<String> paddedArs) {
        Map<String, StopDistrict> out = new LinkedHashMap<>();
        if (paddedArs.isEmpty()) {
            return out;
        }
        jdbc.query(STOP_SQL, new MapSqlParameterSource("ars", List.copyOf(paddedArs)), rs -> {
            out.put(rs.getString("ars"), new StopDistrict(rs.getString("adm_cd"), rs.getString("adm_nm")));
        });
        return out;
    }

    /** Simplified GeoJSON boundary for each district code. */
    public Map<String, String> boundaries(Collection<String> codes) {
        Map<String, String> out = new LinkedHashMap<>();
        if (codes.isEmpty()) {
            return out;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("codes", List.copyOf(codes))
                .addValue("tolerance", SIMPLIFY_TOLERANCE);
        jdbc.query(BOUNDARY_SQL, params, rs -> {
            out.put(rs.getString("adm_cd"), rs.getString("geometry"));
        });
        return out;
    }
}
