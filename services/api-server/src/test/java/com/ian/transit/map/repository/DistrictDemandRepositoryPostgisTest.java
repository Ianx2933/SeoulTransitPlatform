package com.ian.transit.map.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ian.transit.TestcontainersConfiguration;
import com.ian.transit.map.dto.DistrictDemandResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration tests for district-demand spatial semantics against real PostGIS.
 *
 * The fixture intentionally includes a stop exactly on the polygon boundary.
 * ST_Covers must include it; ST_Contains would not. This locks an important
 * geospatial decision into an executable regression test.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.properties.hibernate.hbm2ddl.auto=none",
        "spring.sql.init.mode=never"
})
class DistrictDemandRepositoryPostgisTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DistrictDemandRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis");
        jdbcTemplate.execute("DROP TABLE IF EXISTS bus_stop_demand_node_mapping");
        jdbcTemplate.execute("DROP TABLE IF EXISTS integrated_bus_stop_location");
        jdbcTemplate.execute("DROP TABLE IF EXISTS bus_stop_location");
        jdbcTemplate.execute("DROP TABLE IF EXISTS integrated_hourly_transit_demand_light");
        jdbcTemplate.execute("DROP TABLE IF EXISTS admin_dong_boundary");

        jdbcTemplate.execute("""
                CREATE TABLE admin_dong_boundary (
                    adm_cd VARCHAR,
                    adm_nm VARCHAR,
                    geom geometry(MultiPolygon, 4326)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE bus_stop_location (
                    "정류장번호" VARCHAR,
                    "정류장명" VARCHAR,
                    "위도" DOUBLE PRECISION,
                    "경도" DOUBLE PRECISION,
                    geom geometry(Point, 4326)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE integrated_hourly_transit_demand_light (
                    mode VARCHAR,
                    service_id VARCHAR,
                    node_id VARCHAR,
                    node_name VARCHAR,
                    day_type VARCHAR,
                    hour INTEGER,
                    boarding DOUBLE PRECISION,
                    alighting DOUBLE PRECISION,
                    source VARCHAR
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE integrated_bus_stop_location (
                    canonical_node_id VARCHAR PRIMARY KEY,
                    stop_name VARCHAR,
                    lat DOUBLE PRECISION,
                    lng DOUBLE PRECISION,
                    geom geometry(Point, 4326)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE bus_stop_demand_node_mapping (
                    service_id VARCHAR,
                    demand_node_id VARCHAR,
                    canonical_node_id VARCHAR
                )
                """);

        jdbcTemplate.execute("""
                INSERT INTO admin_dong_boundary (adm_cd, adm_nm, geom)
                VALUES (
                    'TEST0001',
                    'Test District',
                    ST_GeomFromText(
                        'MULTIPOLYGON(((127.0 37.0, 128.0 37.0, 128.0 38.0, 127.0 38.0, 127.0 37.0)))',
                        4326
                    )
                )
                """);

        insertBusStop("00001", "Inside Stop", 37.5, 127.5);
        insertBusStop("00002", "Boundary Stop", 37.5, 127.0);
        insertBusStop("00003", "Outside Stop", 37.5, 128.5);

        insertDemand("R1", "1", 10, 1);
        insertDemand("R1", "2", 20, 2);
        insertDemand("R1", "3", 30, 3);
    }

    @Test
    void nodeOnDistrictBoundaryIsIncludedButOutsideNodeIsExcluded() {
        DistrictDemandResponse response = repository.findDistrictDemand(
                List.of("TEST0001"),
                List.of("bus"),
                List.of("mon"),
                "sum",
                List.of(8),
                null
        );

        assertEquals(30L, response.boarding());
        assertEquals(3L, response.alighting());
        assertEquals(33L, response.total());
        assertEquals(2L, response.totalNodeCount());
        assertEquals(2, response.nodes().size());

        List<String> nodeIds = response.nodes().stream()
                .map(DistrictDemandResponse.NodeDemand::nodeId)
                .toList();

        assertTrue(nodeIds.contains("00001"));
        assertTrue(nodeIds.contains("00002"));
    }

    @Test
    void nodeLimitDoesNotChangeAggregatedTotals() {
        DistrictDemandResponse response = repository.findDistrictDemand(
                List.of("TEST0001"),
                List.of("bus"),
                List.of("mon"),
                "sum",
                List.of(8),
                1
        );

        assertEquals(30L, response.boarding());
        assertEquals(3L, response.alighting());
        assertEquals(33L, response.total());
        assertEquals(2L, response.totalNodeCount());
        assertEquals(1, response.nodes().size());
    }

    private void insertBusStop(String stopNumber, String stopName, double lat, double lng) {
        jdbcTemplate.update(
                """
                INSERT INTO bus_stop_location ("정류장번호", "정류장명", "위도", "경도", geom)
                VALUES (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                """,
                stopNumber,
                stopName,
                lat,
                lng,
                lng,
                lat
        );
    }

    private void insertDemand(
            String serviceId,
            String nodeId,
            double boarding,
            double alighting
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO integrated_hourly_transit_demand_light
                    (mode, service_id, node_id, node_name, day_type, hour, boarding, alighting, source)
                VALUES ('bus', ?, ?, ?, 'mon', 8, ?, ?, 'test')
                """,
                serviceId,
                nodeId,
                "Stop " + nodeId,
                boarding,
                alighting
        );
    }
}
