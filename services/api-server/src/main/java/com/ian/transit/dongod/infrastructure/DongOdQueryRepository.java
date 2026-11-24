package com.ian.transit.dongod.infrastructure;

import com.ian.transit.dongod.application.BusOutside;
import com.ian.transit.dongod.application.DongArea;
import com.ian.transit.dongod.application.DongReach;
import com.ian.transit.dongod.application.DongRouteFlows;
import com.ian.transit.dongod.dto.DongOdResponse;
import com.ian.transit.dongod.dto.DongOdSummaryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads district OD (od_dong_typical) and the bus routes boarding in an area
 * (od_typical_route_stop), for a 행정동, a 구 or all of Seoul (DongArea).
 * Queries read one area's rows, or one row per unit for the summary.
 */
@Repository
@RequiredArgsConstructor
public class DongOdQueryRepository {

    /**
     * Riders boarding at bus stops inside the district, by route and the
     * district of the alighting stop. Both ends come from the stop lookups of
     * database/schema/11_stop_area_lookup.sql: area stops from stop_area_any,
     * the alighting stop's district from stop_area_primary (stop_location_fix,
     * then bus_stop_location, then integrated_bus_stop_location, so stops only
     * the integrated table knows have a district too: 452, 2026-11-17). ARS
     * numbers are matched with and without leading zeros.
     */
    private static final String ROUTES_SQL = RouteLoadQueryRepository.AREA_STOPS + """
            ,
            legs AS MATERIALIZED (
                SELECT o.노선명 AS route, o.하차_정류장ars AS alight_ars, o.승객수 AS passengers
                FROM od_typical_route_stop o
                WHERE o.기준일자 = :label
                  AND o.승차_정류장ars IN (SELECT ars FROM stop_keys)
            )
            SELECT l.route, sp.adm_cd, sp.adm_nm, SUM(l.passengers) AS passengers
            FROM legs l
            LEFT JOIN stop_area_primary sp ON sp.ars = LPAD(l.alight_ars, 5, '0')
            GROUP BY l.route, sp.adm_cd, sp.adm_nm
            """;

    /**
     * Bus riders between the area's stops and stops outside Seoul (route stop
     * OD), by the 행정동 of the stop at the other end, with the straight-line km
     * between the two districts' points (as reachRows). %1$s = area filter on
     * adm_cd, %2$s = this end's ARS column, %3$s = the other end's (both fixed
     * in code). The other end is placed like the bus routes tab (fix row first,
     * then bus_stop_location, then integrated_bus_stop_location; lowest adm_cd
     * on a boundary). This end's district is the area district holding its
     * stop, else the area's own point. Rows with adm_cd null are riders whose
     * other stop could not be placed; rows inside Seoul are left out.
     */
    private static final String BUS_OUTSIDE_SQL = """
            ,
            dong AS (
                SELECT ST_Union(geom) AS g FROM public.admin_dong_boundary WHERE %1$s
            ),
            area_dongs AS (
                SELECT adm_cd, ST_Union(geom) AS g, ST_PointOnSurface(ST_Union(geom)) AS c
                FROM public.admin_dong_boundary WHERE %1$s
                GROUP BY adm_cd
            ),
            legs AS MATERIALIZED (
                SELECT LPAD(o.%2$s, 5, '0') AS here_ars, LPAD(o.%3$s, 5, '0') AS there_ars,
                       SUM(o.승객수) AS passengers
                FROM od_typical_route_stop o
                WHERE o.기준일자 = :label
                  AND o.%2$s IN (SELECT ars FROM stop_keys)
                  AND o.%3$s NOT IN (SELECT ars FROM stop_keys)
                GROUP BY 1, 2
            ),
            here_dong AS (
                SELECT DISTINCT ON (p.ars) p.ars, a.c
                FROM stop_area_primary p
                JOIN area_dongs a ON ST_Covers(a.g, p.geom)
                WHERE p.ars IN (SELECT here_ars FROM legs)
                ORDER BY p.ars, a.adm_cd
            ),
            there_dong AS (
                SELECT p.ars, p.adm_cd, p.adm_nm
                FROM stop_area_primary p
                WHERE p.ars IN (SELECT there_ars FROM legs)
            ),
            there_pts AS (
                SELECT adm_cd, p AS c
                FROM dong_point
                WHERE adm_cd IN (SELECT adm_cd FROM there_dong WHERE adm_cd NOT LIKE '11%%')
            )
            SELECT td.adm_cd, td.adm_nm,
                   CASE WHEN tp.c IS NULL THEN NULL
                        ELSE ST_DistanceSphere(COALESCE(hd.c, ST_PointOnSurface(dong.g)), tp.c) / 1000.0 END AS km,
                   SUM(l.passengers) AS passengers
            FROM legs l
            CROSS JOIN dong
            LEFT JOIN here_dong hd ON hd.ars = l.here_ars
            LEFT JOIN there_dong td ON td.ars = l.there_ars
            LEFT JOIN there_pts tp ON tp.adm_cd = td.adm_cd
            WHERE td.adm_cd IS NULL OR td.adm_cd NOT LIKE '11%%'
            GROUP BY 1, 2, 3
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public record Totals(double departures, double arrivals, double subwayDepartures,
                         double busDepartures, String dongName) {
    }

    private static MapSqlParameterSource params(String dayType, DongArea level, String code) {
        return new MapSqlParameterSource().addValue("dayType", dayType).addValues(level.params(code));
    }

    /** Rows of the area's OD grouped to destLevel units (or origins, with from = false). */
    public List<DongOdResponse.Flow> flows(String dayType, DongArea level, String code, DongArea destLevel,
                                           boolean from, int limit) {
        String self = from ? "origin_cd" : "dest_cd";
        String other = from ? "dest_cd" : "origin_cd";
        String otherName = from ? "dest_name" : "origin_name";
        String sql = """
                SELECT %s AS code, MIN(%s) AS dong_name,
                       SUM(subway_rides) AS subway, SUM(bus_rides) AS bus, SUM(total_rides) AS total
                FROM od_dong_typical
                WHERE day_type = :dayType AND %s
                GROUP BY 1
                ORDER BY SUM(total_rides) DESC
                LIMIT :limit
                """.formatted(destLevel.unitSql(other), otherName, level.filterSql(self));
        MapSqlParameterSource p = params(dayType, level, code).addValue("limit", limit);
        return jdbc.query(sql, p, (rs, i) -> {
            String unit = rs.getString("code");
            return new DongOdResponse.Flow(unit, destLevel.nameOf(unit, rs.getString("dong_name")),
                    rs.getDouble("subway"), rs.getDouble("bus"), rs.getDouble("total"));
        });
    }

    public Totals totals(String dayType, DongArea level, String code) {
        String sql = """
                SELECT
                    (SELECT COALESCE(SUM(total_rides), 0) FROM od_dong_typical
                     WHERE day_type = :dayType AND %1$s) AS departures,
                    (SELECT COALESCE(SUM(subway_rides), 0) FROM od_dong_typical
                     WHERE day_type = :dayType AND %1$s) AS subway_departures,
                    (SELECT COALESCE(SUM(bus_rides), 0) FROM od_dong_typical
                     WHERE day_type = :dayType AND %1$s) AS bus_departures,
                    (SELECT COALESCE(SUM(total_rides), 0) FROM od_dong_typical
                     WHERE day_type = :dayType AND %2$s) AS arrivals,
                    (SELECT MIN(adm_nm) FROM public.admin_dong_boundary WHERE adm_cd = :code) AS dong_name
                """.formatted(level.filterSql("origin_cd"), level.filterSql("dest_cd"));
        return jdbc.queryForObject(sql, params(dayType, level, code), (rs, i) -> new Totals(
                rs.getDouble("departures"), rs.getDouble("arrivals"),
                rs.getDouble("subway_departures"), rs.getDouble("bus_departures"), rs.getString("dong_name")));
    }

    /** Departures and arrivals by mode for every unit of the level inside Seoul. */
    public List<DongOdSummaryRow> summary(String dayType, DongArea level) {
        String sql = """
                WITH dep AS (
                    SELECT %1$s AS code, SUM(total_rides) AS t, SUM(subway_rides) AS s, SUM(bus_rides) AS b
                    FROM od_dong_typical WHERE day_type = :dayType AND origin_cd <> 'outside'
                    GROUP BY 1
                ), arr AS (
                    SELECT %2$s AS code, SUM(total_rides) AS t, SUM(subway_rides) AS s, SUM(bus_rides) AS b
                    FROM od_dong_typical WHERE day_type = :dayType AND dest_cd <> 'outside'
                    GROUP BY 1
                )
                SELECT COALESCE(dep.code, arr.code) AS code,
                       COALESCE(dep.t, 0) AS departures, COALESCE(arr.t, 0) AS arrivals,
                       COALESCE(dep.s, 0) AS subway_departures, COALESCE(dep.b, 0) AS bus_departures,
                       COALESCE(arr.s, 0) AS subway_arrivals, COALESCE(arr.b, 0) AS bus_arrivals
                FROM dep FULL JOIN arr ON dep.code = arr.code
                """.formatted(level.unitSql("origin_cd"), level.unitSql("dest_cd"));
        return jdbc.query(sql, new MapSqlParameterSource("dayType", dayType), (rs, i) -> new DongOdSummaryRow(
                rs.getString("code"), rs.getDouble("departures"), rs.getDouble("arrivals"),
                rs.getDouble("subway_departures"), rs.getDouble("bus_departures"),
                rs.getDouble("subway_arrivals"), rs.getDouble("bus_arrivals")));
    }

    /**
     * District OD rows of the area with the straight-line km between the two
     * districts' points (0 inside one district, null to or from outside).
     * from = rows leaving the area, otherwise rows arriving in it.
     */
    public List<DongReach.Row> reachRows(String dayType, DongArea level, String code, boolean from) {
        String self = from ? "origin_cd" : "dest_cd";
        String other = from ? "dest_cd" : "origin_cd";
        String sql = """
                WITH f AS MATERIALIZED (
                    SELECT %1$s AS here, %2$s AS there, subway_rides, bus_rides, total_rides
                    FROM od_dong_typical
                    WHERE day_type = :dayType AND %3$s
                ),
                pts AS (
                    SELECT adm_cd, p
                    FROM dong_point
                    WHERE adm_cd IN (SELECT here FROM f UNION SELECT there FROM f)
                )
                SELECT CASE WHEN f.there = 'outside' OR f.here = 'outside' OR a.p IS NULL OR b.p IS NULL THEN NULL
                            WHEN f.here = f.there THEN 0
                            ELSE ST_DistanceSphere(a.p, b.p) / 1000.0 END AS km,
                       (f.there = 'outside' OR f.here = 'outside') AS outside,
                       f.subway_rides, f.bus_rides, f.total_rides
                FROM f
                LEFT JOIN pts a ON a.adm_cd = f.here
                LEFT JOIN pts b ON b.adm_cd = f.there
                """.formatted(self, other, level.filterSql(self));
        return jdbc.query(sql, params(dayType, level, code), (rs, i) -> new DongReach.Row(
                rs.getObject("km", Double.class), rs.getBoolean("outside"),
                rs.getDouble("subway_rides"), rs.getDouble("bus_rides"), rs.getDouble("total_rides")));
    }

    /**
     * Bus rows between the area and districts outside Seoul (BUS_OUTSIDE_SQL).
     * from = boarding in the area, otherwise alighting in it. Not for all of
     * Seoul: every stop would be an area stop.
     */
    public List<BusOutside.Row> busOutsideRows(String label, DongArea level, String code, boolean from) {
        if (level == DongArea.CITY) {
            throw new IllegalArgumentException("bus outside Seoul is for a dong, a gu or a group, not all of Seoul");
        }
        String here = from ? "승차_정류장ars" : "하차_정류장ars";
        String there = from ? "하차_정류장ars" : "승차_정류장ars";
        String filter = level.filterSql("adm_cd");
        String sql = RouteLoadQueryRepository.AREA_STOPS.formatted(filter) + BUS_OUTSIDE_SQL.formatted(filter, here, there);
        MapSqlParameterSource p = params(null, level, code).addValue("label", label);
        return jdbc.query(sql, p, (rs, i) -> new BusOutside.Row(rs.getString("adm_cd"), rs.getString("adm_nm"),
                rs.getObject("km", Double.class), rs.getDouble("passengers")));
    }

    /**
     * The 행정동 of each stop (padded ARS), placed as the bus routes tab places
     * alighting stops: stop_location_fix first, then bus_stop_location, then
     * integrated_bus_stop_location; the lowest adm_cd on a boundary. Stops
     * without a location or district are missing from the map.
     */
    private static final String STOP_DONG_SQL = """
            SELECT ars, adm_cd, adm_nm FROM stop_area_primary WHERE ars IN (:ars)
            """;

    public record StopDong(String code, String name) {
    }

    /** [lat, lng] of each district's point (ST_PointOnSurface of its boundary), as reachRows uses. */
    public Map<String, double[]> dongPoints(Collection<String> codes) {
        Map<String, double[]> out = new HashMap<>();
        if (codes.isEmpty()) {
            return out;
        }
        String sql = """
                SELECT adm_cd, ST_Y(p) AS lat, ST_X(p) AS lng
                FROM dong_point
                WHERE adm_cd IN (:codes)
                """;
        jdbc.query(sql, new MapSqlParameterSource("codes", List.copyOf(codes)), rs -> {
            out.put(rs.getString("adm_cd"), new double[] {rs.getDouble("lat"), rs.getDouble("lng")});
        });
        return out;
    }

    /** [lat, lng] of the area's point (ST_PointOnSurface of the union), the fallback in BUS_OUTSIDE_SQL. */
    public double[] areaPoint(DongArea level, String code) {
        String sql = """
                SELECT ST_Y(p) AS lat, ST_X(p) AS lng
                FROM (SELECT ST_PointOnSurface(ST_Union(geom)) AS p FROM public.admin_dong_boundary WHERE %s) x
                """.formatted(level.filterSql("adm_cd"));
        List<double[]> r = jdbc.query(sql, params(null, level, code),
                (rs, i) -> new double[] {rs.getDouble("lat"), rs.getDouble("lng")});
        return r.isEmpty() ? null : r.get(0);
    }

    public Map<String, StopDong> stopDongs(Collection<String> paddedArs) {
        Map<String, StopDong> out = new HashMap<>();
        if (paddedArs.isEmpty()) {
            return out;
        }
        jdbc.query(STOP_DONG_SQL, new MapSqlParameterSource("ars", List.copyOf(paddedArs)), rs -> {
            out.put(rs.getString("ars"), new StopDong(rs.getString("adm_cd"), rs.getString("adm_nm")));
        });
        return out;
    }

    /** Bus routes boarding inside a 행정동 or a 구 (not all of Seoul: too many stops). */
    public List<DongRouteFlows.Row> routeRows(String label, DongArea level, String code) {
        if (level == DongArea.CITY) {
            throw new IllegalArgumentException("bus routes are listed for a dong or a gu, not all of Seoul");
        }
        MapSqlParameterSource p = params(null, level, code).addValue("label", label);
        String sql = ROUTES_SQL.formatted(level.filterSql("adm_cd"));
        return jdbc.query(sql, p, (rs, i) -> new DongRouteFlows.Row(
                rs.getString("route"), rs.getString("adm_cd"),
                DongArea.placeName(rs.getString("adm_cd"), rs.getString("adm_nm")), rs.getDouble("passengers")));
    }
}
