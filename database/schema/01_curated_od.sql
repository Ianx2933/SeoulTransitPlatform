-- 01_curated_od.sql
-- Curated OD table and holiday reference.
--
-- IMPORTANT / 중요:
-- analysis_table_final is the ONLY table mapped by a JPA @Entity
-- (com.ian.transit.curatedod.infrastructure.CuratedOdRecord).
-- Because application.yaml uses `ddl-auto: validate`, the API server will
-- FAIL TO START if this table or any of its columns is missing.
-- Every other table is accessed through JdbcTemplate and therefore fails
-- only at request time, not at boot.
--
-- Column names and types are derived from CuratedOdRecord.java.
-- Do not rename columns without changing the entity.
--
-- anomaly_data mirrors this column list exactly; it is the quarantine target
-- for rows removed by the correction pipeline.

-- NO PRIMARY KEY IS DEFINED HERE. This is deliberate.
--
-- The JPA entity declares a composite @IdClass of
-- (기준일자, 노선명, 승차_정류장ars, 하차_정류장ars), but that combination is NOT
-- unique in the actual data. OdCorrectionCommandRepository ranks rows with
-- ROW_NUMBER() OVER (PARTITION BY 노선명, 승차_정류장ars) over differing
-- 승차_정류장순번 values, which only makes sense when duplicates exist.
--
-- A database-level primary key on those columns would therefore:
--   1. reject the curated OD load, and
--   2. break the correction pipeline, which UPDATEs 승차_정류장ars and
--      하차_정류장ars in place and would collide mid-statement.
--
-- Hibernate `validate` checks tables and columns only, not keys or
-- constraints, so omitting the key costs nothing at startup.

CREATE TABLE IF NOT EXISTS public.analysis_table_final (
    기준일자                VARCHAR(8),
    노선명                  VARCHAR(50),
    전환_노선id             BIGINT,
    승차_정류장순번          INTEGER,
    승차_정류장ars          VARCHAR(5),
    승차_정류장표준코드       VARCHAR(20),
    승차_정류장명            VARCHAR(200),
    하차_정류장순번          INTEGER,
    하차_정류장ars          VARCHAR(5),
    하차_정류장표준코드       VARCHAR(20),
    하차_정류장명            VARCHAR(200),
    승객수                  INTEGER
);

-- Lookup index for date + route filtering used by congestion and OD APIs.
CREATE INDEX IF NOT EXISTS idx_analysis_table_final_date_route
ON public.analysis_table_final (기준일자, 노선명);

-- Boarding-stop sequence lookup used by the OD correction pipeline.
CREATE INDEX IF NOT EXISTS idx_analysis_table_final_route_seq
ON public.analysis_table_final (노선명, 승차_정류장순번);

-- Quarantine table for rows removed during OD correction.
-- Column order must match analysis_table_final because the pipeline uses
-- INSERT INTO anomaly_data SELECT a.* ... with no explicit column list.
CREATE TABLE IF NOT EXISTS public.anomaly_data (
    기준일자                VARCHAR(8),
    노선명                  VARCHAR(50),
    전환_노선id             BIGINT,
    승차_정류장순번          INTEGER,
    승차_정류장ars          VARCHAR(5),
    승차_정류장표준코드       VARCHAR(20),
    승차_정류장명            VARCHAR(200),
    하차_정류장순번          INTEGER,
    하차_정류장ars          VARCHAR(5),
    하차_정류장표준코드       VARCHAR(20),
    하차_정류장명            VARCHAR(200),
    승객수                  INTEGER
);

-- Holiday reference used by PredictionFeatureService and model training.
CREATE TABLE IF NOT EXISTS public.holiday_config (
    날짜   VARCHAR(8) PRIMARY KEY,
    설명   VARCHAR(100)
);