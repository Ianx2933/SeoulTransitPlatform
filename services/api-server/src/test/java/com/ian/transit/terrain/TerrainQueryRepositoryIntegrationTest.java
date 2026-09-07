package com.ian.transit.terrain;

import com.ian.transit.terrain.api.TerrainUnavailableException;
import com.ian.transit.terrain.infrastructure.TerrainQueryRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.sql.DriverManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TerrainQueryRepositoryIntegrationTest {
    @Container static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                    .asCompatibleSubstituteFor("postgres")).withDatabaseName("terrain_test");
    JdbcTemplate jdbc;
    TerrainQueryRepository repository;

    @BeforeAll void init() throws Exception {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        jdbc=new JdbcTemplate(ds);
        repository=new TerrainQueryRepository(jdbc);
        // Reject fixture drift before executing the real migration in PostGIS.
        // Maven runs Surefire from services/api-server, including with -f.
        Path canonical = Path.of("..", "..", "database", "terrain", "sql", "01_terrain_schema.sql").toAbsolutePath().normalize();
        String migration = Files.readString(canonical, StandardCharsets.UTF_8);
        String fixture;
        try (var stream = Objects.requireNonNull(getClass().getResourceAsStream("/terrain-schema.sql"))) {
            fixture = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(migration, fixture, "Terrain test schema differs from the canonical migration");
        try(var conn=DriverManager.getConnection(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
            var statement=conn.createStatement()) {
            statement.execute(migration);
        }
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE public.terrain_active_dataset,public.terrain_stop_dong_assignment,public.bus_stop_terrain_stats,public.terrain_dataset CASCADE");
    }
    void dataset(String id) {
        jdbc.update("INSERT INTO public.terrain_dataset(dataset_id,source_id,slope_method,boundary_base_date,processed_at) VALUES (?,'GLO-30','test','20260101',now())",id);
    }
    void stop(String version,String id,Double elev,Double slope,String dong) {
        jdbc.update("INSERT INTO public.bus_stop_terrain_stats VALUES (?,?,?, ?,ST_SetSRID(ST_MakePoint(127,37),4326),?,?)",
                version,id,id,"Stop "+id,elev,slope);
        jdbc.update("INSERT INTO public.terrain_stop_dong_assignment VALUES (?,?,?,?,?,?)",
                version,id,dong,dong==null?null:"Dong "+dong,dong==null?"unmatched":"interior",dong==null?0:1);
    }
    void publish(String id,boolean activate) {
        jdbc.update("""
            UPDATE public.terrain_dataset SET published_at=now(),
            stop_count=(SELECT COUNT(*) FROM public.bus_stop_terrain_stats WHERE dataset_id=?),
            measured_stop_count=(SELECT COUNT(slope_pct) FROM public.bus_stop_terrain_stats WHERE dataset_id=?),
            assigned_stop_count=(SELECT COUNT(*) FROM public.terrain_stop_dong_assignment WHERE dataset_id=? AND adm_cd IS NOT NULL)
            WHERE dataset_id=?""",id,id,id,id);
        if(activate) jdbc.update("INSERT INTO public.terrain_active_dataset VALUES(TRUE,?,now())",id);
    }
    @Test void noActiveDatasetIsUnavailable() {
        assertThrows(TerrainUnavailableException.class,repository::requireActiveDataset);
    }
    @Test void countsNullsAndUsesInclusiveThreshold() {
        dataset("v1");
        stop("v1","001",null,7.99,"A");
        stop("v1","002",null,8.0,"A");
        stop("v1","003",12.0,8.01,"A");
        stop("v1","004",null,null,"A");
        stop("v1","005",null,null,"B");
        stop("v1","006",null,120.0,null);
        publish("v1",true);
        var a=repository.summariseByDong("v1",8,0).get(0);
        assertEquals("A",a.admCd());
        assertEquals(4,a.totalStops());assertEquals(3,a.measuredStops());
        assertEquals(1,a.missingSlopeStops());assertEquals(2,a.steepStops());
        assertEquals(8.0,a.avgSlopePct());assertEquals(8.01,a.maxSlopePct());
        assertEquals(12.0,a.avgElevM());
        var b=repository.summariseByDong("v1",8,0).get(1);
        assertEquals("B",b.admCd());assertEquals(0,b.measuredStops());
        assertNull(b.avgSlopePct());assertNull(b.avgElevM());
        var list=repository.findStopsAboveSlope("v1",8,100,0);
        assertEquals(3,list.size());
        assertEquals("006",list.get(0).nodeId());assertNull(list.get(0).dongName());
        assertEquals("003", list.get(1).nodeId());
        assertEquals(12.0, list.get(1).elevM());
        assertEquals("002", list.get(2).nodeId());
        assertNull(list.get(2).elevM());
        assertEquals("002",repository.findStopsAboveSlope("v1",8,1,2).get(0).nodeId());
        assertEquals(1,repository.summariseByDong("v1",8,3).size());
    }
    @Test void incompletePublicationIsRejected() {
        dataset("incomplete");
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE public.terrain_dataset SET published_at=now(),stop_count=1 WHERE dataset_id='incomplete'"));
    }
    @Test void publicationRejectsIncorrectAssignmentAndAmbiguityCounts() {
        dataset("counts");
        stop("counts", "001", 10.0, 8.0, "A");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
            UPDATE public.terrain_dataset SET published_at=now(),
            stop_count=1, measured_stop_count=1, assigned_stop_count=0
            WHERE dataset_id='counts'"""));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
            UPDATE public.terrain_dataset SET published_at=now(),
            stop_count=1, measured_stop_count=1, assigned_stop_count=1,
            ambiguous_boundary_stop_count=1 WHERE dataset_id='counts'"""));
        publish("counts", false);
        assertEquals(1L, jdbc.queryForObject("""
            SELECT assigned_stop_count FROM public.terrain_dataset
            WHERE dataset_id='counts'""", Long.class));
    }
    @Test void publicationRejectsMissingAssignmentRows() {
        dataset("missing");
        jdbc.update("""
            INSERT INTO public.bus_stop_terrain_stats
            (dataset_id,node_id,stop_no,stop_name,geom,elev_m,slope_pct)
            VALUES ('missing','001','001','Stop',ST_SetSRID(ST_MakePoint(127,37),4326),10,8)""");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> publish("missing", false));
    }
    @Test void unpublishedDatasetCannotBeActivated() {
        dataset("unpublished");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
            "INSERT INTO public.terrain_active_dataset VALUES(TRUE,'unpublished',now())"));
    }
    @Test void versionIsolationAndPublishedSnapshotGuards() {
        dataset("v1");stop("v1","001",1.0,8.0,"A");publish("v1",true);
        dataset("v2");stop("v2","001",2.0,12.0,"A");publish("v2",false);
        assertEquals("v1",repository.requireActiveDataset().datasetId());
        assertEquals(8.0,repository.findStopsAboveSlope("v1",0,10,0).get(0).slopePct());
        assertEquals(12.0,repository.findStopsAboveSlope("v2",0,10,0).get(0).slopePct());
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "UPDATE public.bus_stop_terrain_stats SET slope_pct=0 WHERE dataset_id='v1'"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update(
            "DELETE FROM public.terrain_dataset WHERE dataset_id='v1'"));
        jdbc.update("UPDATE public.terrain_active_dataset SET dataset_id='v2',activated_at=now() WHERE singleton=TRUE");
        assertEquals("v2",repository.requireActiveDataset().datasetId());
    }
}
