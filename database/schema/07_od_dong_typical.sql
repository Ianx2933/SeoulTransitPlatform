-- 07_od_dong_typical.sql
-- Typical-day district-to-district (행정동) OD from TOPIS, all modes.
--
-- Source: BigQuery dbt_dev.fct_od_dong_mode_typical (TOPIS 동별 수단 OD, mean of
-- the published days of each day type in the last twelve months, special days
-- left out). District names are turned into admin_dong_boundary codes with
-- dbt_dev.stg_stop_dong (gu, dong -> adm_cd; all 426 Seoul districts match).
-- TOPIS "서울시외" (outside Seoul) is kept as the code 'outside'.
--
-- About 244k rows, a few tens of MB. Loaded by ops/od_dong/load_od_dong.md.

CREATE TABLE IF NOT EXISTS od_dong_typical (
    day_type      VARCHAR(20)      NOT NULL,   -- weekday | saturday | sunday_holiday
    origin_cd     VARCHAR(10)      NOT NULL,   -- adm_cd or 'outside'
    origin_name   VARCHAR(100),
    dest_cd       VARCHAR(10)      NOT NULL,
    dest_name     VARCHAR(100),
    subway_rides  DOUBLE PRECISION NOT NULL DEFAULT 0,
    bus_rides     DOUBLE PRECISION NOT NULL DEFAULT 0,
    total_rides   DOUBLE PRECISION NOT NULL DEFAULT 0,
    PRIMARY KEY (day_type, origin_cd, dest_cd)
);

CREATE INDEX IF NOT EXISTS idx_od_dong_typical_dest
    ON od_dong_typical (day_type, dest_cd);
