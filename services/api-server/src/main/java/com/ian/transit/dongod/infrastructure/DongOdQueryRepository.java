package com.ian.transit.dongod.infrastructure;

import com.ian.transit.dongod.application.DongRouteFlows;
import com.ian.transit.dongod.dto.DongOdResponse;
import com.ian.transit.dongod.dto.DongOdSummaryRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads district OD (od_dong_typical) and the bus routes boarding in a
 * district (od_typical_route_stop). All queries are small: one district's
 * rows, or one row per district for the summary.
 */
@Repository
@RequiredArgsConstructor
public class DongOdQueryRepository {

    private static final String FLOWS_FROM_SQL = """
            SELECT dest_cd AS code, dest_name AS name, subway_rides, bus_rides, total_rides
            FROM od_dong_typical
            WHERE day_type = :dayType AND origin_cd = :code
            ORDER BY total_rides DESC
            LIMIT :limit
            """;

    private static final String FLOWS_TO_SQL = """
            SELECT origin_cd AS code, origin_name AS name, subway_rides, bus_rides, total_rides
            FROM od_dong_typical
            WHERE day_type = :dayType AND dest_cd = :code
            ORDER BY total_rides DESC
            LIMIT :limit
            """;

    private static final String TOTALS_SQL = """
            SELECT
                (SELECT COALESCE(SUM(total_rides), 0) FROM od_dong_typical
                 WHERE day_type = :dayType AND origin_cd = :code) AS departures,
                (SELECT COALESCE(SUM(total_rides), 0) FROM od_dong_typical
                 WHERE day_type = :dayType AND dest_cd = :code) AS arrivals,
                (SELECT MIN(adm_nm) FROM public.admin_dong_boundary WHERE adm_cd = :code) AS name
            """;

    private static final String SUMMARY_SQL = """
            WITH dep AS (
                SELECT origin_cd AS code, SUM(total_rides) AS v
                FROM od_dong_typical WHERE day_type = :dayType AND origin_cd <> 'outside'
                GROUP BY origin_cd
            ), arr AS (
                SELECT dest_cd AS code, SUM(total_rides) AS v
                FROM od_dong_typical WHERE day_type = :dayType AND dest_cd <> 'outside'
                GROUP BY dest_cd
            )
            SELECT COALESCE(dep.code, arr.code) AS code,
                   COALESCE(dep.v, 0) AS departures,
                   COALESCE(arr.v, 0) AS arrivals
            FROM dep FULL JOIN arr ON dep.code = arr.code
            """;

    /**
     * Riders boarding at bus stops inside the district, by route and the
     * district of the alighting stop. Stops are placed like the district
     * demand query: point COALESCE(geom, lat/lng), ST_Covers, bus_stop_location
     * first, integrated_bus_stop_location for the rest. ARS numbers are
     * matched with and without leading zeros.
     */
    private static final String ROUTES_SQL = """
            WITH dong AS (
                SELECT ST_Union(geom) AS g FROM public.admin_dong_boundary WHERE adm_cd = :code
            ),
            stops AS MATERIALIZED (
                SELECT LPAD("정류장번호"::text, 5, '0') AS ars
                FROM bus_stop_location b, dong
                WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)))
                UNION
                SELECT LPAD(b.ars_id::text, 5, '0')
                FROM integrated_bus_stop_location b, dong
                WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)))
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
                SELECT DISTINCT alight_ars FROM legs
            ),
            alight_dong AS (
                SELECT a.alight_ars, d.adm_cd, d.adm_nm
                FROM alight a
                JOIN LATERAL (
                    SELECT COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)) AS g
                    FROM bus_stop_location b
                    WHERE LPAD(b."정류장번호"::text, 5, '0') = LPAD(a.alight_ars, 5, '0')
                    LIMIT 1
                ) s ON true
                JOIN LATERAL (
                    SELECT x.adm_cd, x.adm_nm
                    FROM public.admin_dong_boundary x
                    WHERE ST_Covers(x.geom, s.g)
                    ORDER BY x.adm_cd
                    LIMIT 1
                ) d ON true
            )
            SELECT l.route, ad.adm_cd, ad.adm_nm, SUM(l.passengers) AS passengers
            FROM legs l
            LEFT JOIN alight_dong ad ON ad.alight_ars = l.alight_ars
            GROUP BY l.route, ad.adm_cd, ad.adm_nm
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public record Totals(String name, double departures, double arrivals) {
    }

    public List<DongOdResponse.Flow> flows(String dayType, String code, boolean from, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("dayType", dayType).addValue("code", code).addValue("limit", limit);
        return jdbc.query(from ? FLOWS_FROM_SQL : FLOWS_TO_SQL, p, (rs, i) -> new DongOdResponse.Flow(
                rs.getString("code"), rs.getString("name"),
                rs.getDouble("subway_rides"), rs.getDouble("bus_rides"), rs.getDouble("total_rides")));
    }

    public Totals totals(String dayType, String code) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("dayType", dayType).addValue("code", code);
        return jdbc.queryForObject(TOTALS_SQL, p, (rs, i) -> new Totals(
                rs.getString("name"), rs.getDouble("departures"), rs.getDouble("arrivals")));
    }

    public List<DongOdSummaryRow> summary(String dayType) {
        return jdbc.query(SUMMARY_SQL, new MapSqlParameterSource("dayType", dayType), (rs, i) -> new DongOdSummaryRow(
                rs.getString("code"), rs.getDouble("departures"), rs.getDouble("arrivals")));
    }

    public List<DongRouteFlows.Row> routeRows(String label, String code) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("label", label).addValue("code", code);
        return jdbc.query(ROUTES_SQL, p, (rs, i) -> new DongRouteFlows.Row(
                rs.getString("route"), rs.getString("adm_cd"), rs.getString("adm_nm"), rs.getDouble("passengers")));
    }
}
