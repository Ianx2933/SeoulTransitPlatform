package com.ian.transit.dongod.infrastructure;

import com.ian.transit.dongod.application.DongArea;
import com.ian.transit.dongod.application.DongReach;
import com.ian.transit.dongod.application.DongRouteFlows;
import com.ian.transit.dongod.dto.DongOdResponse;
import com.ian.transit.dongod.dto.DongOdSummaryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

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
     * district of the alighting stop. Stops are placed like the district
     * demand query: point COALESCE(geom, lat/lng), ST_Covers, bus_stop_location
     * first, integrated_bus_stop_location for the rest. ARS numbers are
     * matched with and without leading zeros. Alighting stops are placed the
     * same way, in one pass over each stop table (alight_pts): they used to be
     * looked up in bus_stop_location only, so riders alighting at stops only
     * integrated_bus_stop_location knows (452, 2026-11-17) had no district.
     */
    private static final String ROUTES_SQL = """
            WITH dong AS (
                SELECT ST_Union(geom) AS g FROM public.admin_dong_boundary WHERE %s
            ),
            stops AS MATERIALIZED (
                SELECT LPAD("정류장번호"::text, 5, '0') AS ars
                FROM bus_stop_location b, dong
                WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)))
                  AND LPAD("정류장번호"::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
                UNION
                SELECT LPAD(b.ars_id::text, 5, '0')
                FROM integrated_bus_stop_location b, dong
                WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)))
                  AND LPAD(b.ars_id::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
                UNION
                SELECT f.ars
                FROM stop_location_fix f, dong
                WHERE ST_Covers(dong.g, ST_SetSRID(ST_MakePoint(f.lng, f.lat), 4326))
            ),
            stop_keys AS MATERIALIZED (
                SELECT ars FROM stops WHERE ars <> '00000'
                UNION
                SELECT LTRIM(ars, '0') FROM stops WHERE ars <> '00000'
            ),
            legs AS MATERIALIZED (
                SELECT o.노선명 AS route, o.하차_정류장ars AS alight_ars, o.승객수 AS passengers
                FROM od_typical_route_stop o
                WHERE o.기준일자 = :label
                  AND o.승차_정류장ars IN (SELECT ars FROM stop_keys)
            ),
            alight AS MATERIALIZED (
                SELECT DISTINCT LPAD(alight_ars, 5, '0') AS ars FROM legs
            ),
            alight_pts AS MATERIALIZED (
                SELECT DISTINCT ON (ars) ars, g
                FROM (
                    SELECT f.ars, ST_SetSRID(ST_MakePoint(f.lng, f.lat), 4326) AS g, 0 AS pri
                    FROM stop_location_fix f
                    WHERE f.ars IN (SELECT ars FROM alight)
                    UNION ALL
                    SELECT LPAD(b."정류장번호"::text, 5, '0') AS ars,
                           COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)) AS g, 1 AS pri
                    FROM bus_stop_location b
                    WHERE LPAD(b."정류장번호"::text, 5, '0') IN (SELECT ars FROM alight)
                    UNION ALL
                    SELECT LPAD(b.ars_id::text, 5, '0'),
                           COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)), 2
                    FROM integrated_bus_stop_location b
                    WHERE LPAD(b.ars_id::text, 5, '0') IN (SELECT ars FROM alight)
                ) x
                WHERE g IS NOT NULL
                ORDER BY ars, pri
            ),
            alight_dong AS (
                SELECT p.ars, d.adm_cd, d.adm_nm
                FROM alight_pts p
                JOIN LATERAL (
                    SELECT x.adm_cd, x.adm_nm
                    FROM public.admin_dong_boundary x
                    WHERE ST_Covers(x.geom, p.g)
                    ORDER BY x.adm_cd
                    LIMIT 1
                ) d ON true
            )
            SELECT l.route, ad.adm_cd, ad.adm_nm, SUM(l.passengers) AS passengers
            FROM legs l
            LEFT JOIN alight_dong ad ON ad.ars = LPAD(l.alight_ars, 5, '0')
            GROUP BY l.route, ad.adm_cd, ad.adm_nm
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
                    SELECT adm_cd, ST_PointOnSurface(ST_Union(geom)) AS p
                    FROM public.admin_dong_boundary
                    WHERE adm_cd IN (SELECT here FROM f UNION SELECT there FROM f)
                    GROUP BY adm_cd
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
