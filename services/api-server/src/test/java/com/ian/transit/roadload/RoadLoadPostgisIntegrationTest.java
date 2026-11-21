package com.ian.transit.roadload;

import com.ian.transit.dongod.application.BusOutside;
import com.ian.transit.dongod.application.DongArea;
import com.ian.transit.dongod.application.DongRouteFlows;
import com.ian.transit.dongod.application.DongRouteLoadService;
import com.ian.transit.dongod.dto.DongRouteLoadResponse;
import com.ian.transit.dongod.infrastructure.DongOdQueryRepository;
import com.ian.transit.dongod.infrastructure.RouteLoadQueryRepository;
import com.ian.transit.roadload.application.RoadLoadService;
import com.ian.transit.roadload.dto.RoadLoadResponse;
import com.ian.transit.roadload.infrastructure.RoadLoadQueryRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The road-load SQL against real PostGIS: district legs (od / do / both),
 * stop and segment look-ups, stored route paths, and the bus-routes tab's
 * alighting district. Plain JDBC like TerrainQueryRepositoryIntegrationTest: no
 * Spring context, the stored-path tables come from the canonical schema file.
 *
 * Fixture (two districts side by side, A = 11010530 west, B = 11010540 east):
 *
 *   stop 01001, 01002 in A (bus_stop_location)
 *   stop 01003 in B (bus_stop_location)
 *   stop 01004 in B, known only to integrated_bus_stop_location (the 452 case)
 *
 *   route 100: 01001 -> 01002 -> 01003 -> 01004
 *   route 200: 01002 -> 01003            (shares the 01002 -> 01003 segment)
 *   route 999: no stored path (a closed route)
 *
 *   OD (typical_weekday): 100 01001->01003 10, 100 01002->01004 5,
 *   100 "1001"->"1002" 2 (unpadded ARS, a trip inside A), 100 01003->01004 7,
 *   200 01002->01003 4, 999 01001->01003 3
 *
 * Outside Seoul (bus reach), on typical_saturday so the rows above stay as they are:
 *   district C = 31191510 (Gyeonggi, 용인시) east of B, stop 31001 in C
 *   (integrated_bus_stop_location only), stop 23999 with no location anywhere
 *   300 01001->31001 6, 300 01002->23999 1, 300 31001->01002 8, 300 01001->01003 4
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoadLoadPostgisIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("road_load_test");

    private static final String A = "11010530";
    private static final String B = "11010540";
    private static final String C = "31191510";

    JdbcTemplate jdbc;
    RouteLoadQueryRepository routeRepo;
    RoadLoadQueryRepository roadRepo;
    DongOdQueryRepository dongRepo;

    @BeforeAll
    void init() throws Exception {
        var ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        var named = new NamedParameterJdbcTemplate(ds);
        routeRepo = new RouteLoadQueryRepository(named);
        roadRepo = new RoadLoadQueryRepository(named);
        dongRepo = new DongOdQueryRepository(named);

        jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        // The stored-path tables exactly as production creates them (Surefire runs from services/api-server).
        Path schema = Path.of("..", "..", "database", "schema", "08_bus_route_path.sql").toAbsolutePath().normalize();
        jdbc.execute(Files.readString(schema, StandardCharsets.UTF_8));
        Path fix = Path.of("..", "..", "database", "schema", "09_stop_location_fix.sql").toAbsolutePath().normalize();
        jdbc.execute(Files.readString(fix, StandardCharsets.UTF_8));

        jdbc.execute("CREATE TABLE admin_dong_boundary (adm_cd VARCHAR, adm_nm VARCHAR, geom geometry(MultiPolygon, 4326))");
        jdbc.execute("""
                CREATE TABLE bus_stop_location ("정류장번호" VARCHAR, "정류장명" VARCHAR,
                    "위도" DOUBLE PRECISION, "경도" DOUBLE PRECISION, geom geometry(Point, 4326))""");
        jdbc.execute("""
                CREATE TABLE integrated_bus_stop_location (ars_id VARCHAR, stop_name VARCHAR,
                    lat DOUBLE PRECISION, lng DOUBLE PRECISION, geom geometry(Point, 4326))""");
        jdbc.execute("""
                CREATE TABLE od_typical_route_stop (기준일자 VARCHAR, 노선명 VARCHAR,
                    승차_정류장순번 INTEGER, 승차_정류장ars VARCHAR, 하차_정류장순번 INTEGER,
                    하차_정류장ars VARCHAR, 승객수 DOUBLE PRECISION)""");

        jdbc.execute("""
                CREATE TABLE od_dong_typical (day_type VARCHAR, origin_cd VARCHAR, dest_cd VARCHAR,
                    origin_name VARCHAR, dest_name VARCHAR,
                    subway_rides DOUBLE PRECISION, bus_rides DOUBLE PRECISION, total_rides DOUBLE PRECISION)""");
        jdbc.update("INSERT INTO od_dong_typical VALUES ('weekday', ?, ?, 'A동', 'A동', 0, 2, 2), "
                + "('weekday', ?, ?, 'A동', 'B동', 1, 2, 3), ('weekday', ?, 'outside', 'A동', 'outside', 1, 0, 1)",
                A, A, A, B, A);

        dong(A, "A동", 127.00, 127.01);
        dong(B, "B동", 127.01, 127.02);
        busStop("01001", 127.002);
        busStop("01002", 127.008);
        busStop("01003", 127.012);
        jdbc.update("INSERT INTO integrated_bus_stop_location VALUES ('01004', 'east', 37.005, 127.018, "
                + "ST_SetSRID(ST_MakePoint(127.018, 37.005), 4326))");

        route("100", "01001", "01002", "01003", "01004");
        route("200", "01002", "01003");

        od("100", "01001", "01003", 10);
        od("100", "01002", "01004", 5);
        od("100", "1001", "1002", 2);
        od("100", "01003", "01004", 7);
        od("200", "01002", "01003", 4);
        od("999", "01001", "01003", 3);

        dong(C, "C동", 127.02, 127.03);
        jdbc.update("INSERT INTO integrated_bus_stop_location VALUES ('31001', 'gyeonggi', 37.005, 127.025, "
                + "ST_SetSRID(ST_MakePoint(127.025, 37.005), 4326))");
        saturday("01001", "31001", 6);
        saturday("01002", "23999", 1);
        saturday("31001", "01002", 8);
        saturday("01001", "01003", 4);

        // Stop and district lookups, built from the rows above as production builds them.
        Path lookups = Path.of("..", "..", "database", "schema", "10_stop_area_lookup.sql").toAbsolutePath().normalize();
        jdbc.execute(Files.readString(lookups, StandardCharsets.UTF_8));
    }

    /** What an operator runs after changing a stop table or stop_location_fix (10_stop_area_lookup.sql). */
    private void refreshLookups() {
        jdbc.execute("REFRESH MATERIALIZED VIEW stop_area_any");
        jdbc.execute("REFRESH MATERIALIZED VIEW stop_area_primary");
        jdbc.execute("REFRESH MATERIALIZED VIEW dong_point");
    }

    private void saturday(String board, String alight, double riders) {
        jdbc.update("INSERT INTO od_typical_route_stop VALUES ('typical_saturday', '300', NULL, ?, NULL, ?, ?)",
                board, alight, riders);
    }

    private void dong(String code, String name, double west, double east) {
        jdbc.update("INSERT INTO admin_dong_boundary VALUES (?, ?, ST_Multi(ST_MakeEnvelope(?, 37.0, ?, 37.01, 4326)))",
                code, name, west, east);
    }

    private void busStop(String ars, double lng) {
        jdbc.update("INSERT INTO bus_stop_location VALUES (?, ?, 37.005, ?, ST_SetSRID(ST_MakePoint(?, 37.005), 4326))",
                ars, "stop " + ars, lng, lng);
    }

    /** A stored route along latitude 37.005: one station per ARS, one segment between neighbours. */
    private void route(String name, String... ars) {
        jdbc.update("INSERT INTO bus_route_path_route (route_nm, od_route_nm, route_id, status, n_stations) "
                + "VALUES (?, ?, ?, 'ok', ?)", name, name, "id" + name, ars.length);
        for (int i = 0; i < ars.length; i++) {
            double lng = lngOf(ars[i]);
            jdbc.update("INSERT INTO bus_route_path_station (route_nm, seq, ars, station_nm, lng, lat, trans_yn, path_idx) "
                    + "VALUES (?, ?, ?, ?, ?, 37.005, false, ?)", name, i + 1, ars[i], "stop " + ars[i], lng, i);
            if (i + 1 < ars.length) {
                double next = lngOf(ars[i + 1]);
                jdbc.update("INSERT INTO bus_route_path_segment (route_nm, seq, from_ars, to_ars, length_m, geom) "
                        + "VALUES (?, ?, ?, ?, 100, ST_SetSRID(ST_MakeLine(ST_MakePoint(?, 37.005), ST_MakePoint(?, 37.005)), 4326))",
                        name, i + 1, ars[i], ars[i + 1], lng, next);
            }
        }
    }

    private static double lngOf(String ars) {
        return switch (ars) {
            case "01001" -> 127.002;
            case "01002" -> 127.008;
            case "01003" -> 127.012;
            default -> 127.018;
        };
    }

    private void od(String route, String board, String alight, double riders) {
        // TOPIS sequence = position on the stored path (01001 = 1 ... 01004 = 4); null for a stop it lacks
        jdbc.update("INSERT INTO od_typical_route_stop VALUES ('typical_weekday', ?, ?, ?, ?, ?, ?)",
                route, seqOf(board), board, seqOf(alight), alight, riders);
    }

    private static Integer seqOf(String ars) {
        return switch (ars.replaceFirst("^0+", "")) {
            case "1001" -> 1;
            case "1002" -> 2;
            case "1003" -> 3;
            case "1004" -> 4;
            default -> null;
        };
    }

    private static Map<String, Double> loads(List<DongRouteLoadResponse.Segment> segments) {
        return segments.stream().collect(Collectors.toMap(s -> s.fromArs() + ">" + s.toArs(), DongRouteLoadResponse.Segment::load));
    }

    @Test
    void districtBothCountsATripInsideTheAreaOnceAndSumsSharedSegments() {
        DongRouteLoadResponse r = new DongRouteLoadService(routeRepo, null).load("weekday", "dong", A, "both");
        // boarding in A: 10 + 5 + 2 + 4 + 3; the 2 inside A also alights in A but counts once
        assertThat(r.passengers()).isEqualTo(24.0);
        assertThat(r.unplacedPassengers()).isEqualTo(3.0);                    // route 999 has no path
        assertThat(r.unplacedRoutes()).extracting(DongRouteLoadResponse.Unplaced::route).containsExactly("999");
        assertThat(loads(r.segments())).containsEntry("01001>01002", 12.0)    // 10 + 2
                .containsEntry("01002>01003", 19.0)                              // 10 + 5 (100) + 4 (200)
                .containsEntry("01003>01004", 5.0);
        DongRouteLoadResponse.Segment shared = r.segments().stream()
                .filter(s -> "01002".equals(s.fromArs())).findFirst().orElseThrow();
        assertThat(shared.byRoute()).containsEntry("100", 15.0).containsEntry("200", 4.0);
        assertThat(shared.fromName()).isEqualTo("stop 01002");
        assertThat(shared.toName()).isEqualTo("stop 01003");
    }

    @Test
    void districtDirectionsPickBoardingOrAlightingLegs() {
        var service = new DongRouteLoadService(routeRepo, null);
        assertThat(service.load("weekday", "dong", A, "od").passengers()).isEqualTo(24.0);
        assertThat(service.load("weekday", "dong", A, "do").passengers()).isEqualTo(2.0);      // only the trip inside A
        // B: boards 01003->01004 (7); alights 10 + 5 + 4 + 3 + 7 (the 7 counted once)
        assertThat(service.load("weekday", "dong", B, "both").passengers()).isEqualTo(29.0);
    }

    @Test
    void segmentLookUpEqualsTheRoutesAddedUpOnThatSegment() {
        var service = new RoadLoadService(roadRepo, routeRepo, null);
        RoadLoadResponse x = service.segment("weekday", "01002", "01003");
        assertThat(x.routes()).containsExactly("100", "200");
        assertThat(x.placedPassengers()).isEqualTo(19.0);                    // 10 + 5 + 4
        assertThat(x.origins()).extracting(RoadLoadResponse.Point::ars).containsExactly("01001", "01002");
        assertThat(x.destinations()).extracting(RoadLoadResponse.Point::ars).containsExactly("01003", "01004");

        RoadLoadResponse y = service.routes("weekday", String.join(",", x.routes()));
        assertThat(loads(y.segments()).get("01002>01003")).isEqualTo(x.placedPassengers());
    }

    @Test
    void stopLookUpMatchesPaddedAndUnpaddedArs() {
        RoadLoadResponse r = new RoadLoadService(roadRepo, routeRepo, null).stop("weekday", "1001", "board");
        assertThat(r.passengers()).isEqualTo(15.0);                           // 10 + 2 ("1001") + 3 (999)
        assertThat(r.placedPassengers()).isEqualTo(12.0);
        assertThat(r.title()).isEqualTo("stop 01001 (01001)");
    }

    @Test
    void busRoutesTabFindsTheDistrictOfStopsOnlyTheIntegratedTableKnows() {
        List<DongRouteFlows.Row> rows = dongRepo.routeRows("typical_weekday", DongArea.DONG, A);
        double toB = rows.stream().filter(row -> B.equals(row.destCode())).mapToDouble(DongRouteFlows.Row::passengers).sum();
        double unknown = rows.stream().filter(row -> row.destCode() == null).mapToDouble(DongRouteFlows.Row::passengers).sum();
        assertThat(toB).isCloseTo(22.0, within(1e-9));                       // 10 + 5 (01004, integrated only) + 4 + 3
        assertThat(unknown).isZero();
    }

    @Test
    void stopLocationFixWinsOverTheStopTables() {
        // 01004 is in B per integrated_bus_stop_location; a fix row puts it in A
        jdbc.update("INSERT INTO stop_location_fix (ars, stop_name, lat, lng, reason) VALUES ('01004', 'east', 37.005, 127.005, 'manual')");
        refreshLookups();
        try {
            List<DongRouteFlows.Row> rows = dongRepo.routeRows("typical_weekday", DongArea.DONG, A);
            double toA = rows.stream().filter(row -> A.equals(row.destCode())).mapToDouble(DongRouteFlows.Row::passengers).sum();
            assertThat(toA).isCloseTo(7.0, within(1e-9));                    // 2 inside A + 5 to 01004
            // alighting in A now: 2 ("1002") + 5 (01002->01004) + 7 (01003->01004)
            assertThat(new DongRouteLoadService(routeRepo, null).load("weekday", "dong", A, "do").passengers()).isEqualTo(14.0);
        } finally {
            jdbc.update("DELETE FROM stop_location_fix");
            refreshLookups();
        }
    }

    @Test
    void reachMeasuresBetweenDistrictPointsAndKeepsOutsideApart() {
        var rows = dongRepo.reachRows("weekday", DongArea.DONG, A, true);
        var r = com.ian.transit.dongod.application.DongReach.summarize(rows);
        assertThat(r.total()).isEqualTo(6.0);
        assertThat(r.outside()).isEqualTo(1.0);
        assertThat(r.outsideBus()).isZero();                                // A -> outside is rail 1, bus 0
        assertThat(r.bands().get(0).total()).isEqualTo(2.0);                 // same district
        assertThat(r.bands().get(1).total()).isEqualTo(3.0);                 // A to B, about 0.9 km
        assertThat(r.medianKm()).isEqualTo(0.9);
        // a group of both districts: every ride inside the group's own districts
        var g = com.ian.transit.dongod.application.DongReach.summarize(
                dongRepo.reachRows("weekday", DongArea.GROUP, A + "," + B, true));
        assertThat(g.total()).isEqualTo(6.0);
    }

    @Test
    void busOutsideSeoulPlacesTheOtherStopInItsDistrictAndMeasuresFromTheAreaDistrict() {
        BusOutside.Result from = BusOutside.summarize(dongRepo.busOutsideRows("typical_saturday", DongArea.DONG, A, true));
        assertThat(from.total()).isEqualTo(6.0);                              // 01001 -> 31001 in C
        assertThat(from.unlocated()).isEqualTo(1.0);                          // 23999 has no location
        assertThat(from.medianKm()).isEqualTo(1.8);                           // A's point to C's point, 0.02 degrees east
        assertThat(from.areas()).extracting(BusOutside.Area::code).containsExactly("3119");
        assertThat(from.areas().get(0).name()).isEqualTo("경기 용인시");

        BusOutside.Result to = BusOutside.summarize(dongRepo.busOutsideRows("typical_saturday", DongArea.DONG, A, false));
        assertThat(to.total()).isEqualTo(8.0);                                // 31001 -> 01002
        assertThat(to.unlocated()).isZero();

        // The gu holds A and B: the ride to 01003 (B) stays inside Seoul and is left out
        BusOutside.Result gu = BusOutside.summarize(dongRepo.busOutsideRows("typical_saturday", DongArea.GU, "11010", true));
        assertThat(gu.total()).isEqualTo(6.0);
        assertThat(gu.unlocated()).isEqualTo(1.0);
    }
}
