-- Typical-day route-level stop OD served from Cloud SQL (2026-11).
--
-- Replaces the dated days of analysis_table_final for serving. Built in BigQuery
-- from every corrected TOPIS OD day of the last twelve months (transit-bigquery:
-- fct_od_typical, od_typical_route_stop_serving) and loaded by
-- pipelines/od_typical/load_typical_od.py, which writes od_typical_route_stop__next
-- and swaps it in.
--
-- The columns follow analysis_table_final so code that selected a date can select
-- a typical day: 기준일자 holds a label (typical_weekday, typical_saturday,
-- typical_sunday_holiday) and day_type the same day type without the prefix.
-- 승객수 is a mean over n_days days, so it is a real number. A pair absent on a
-- day counts as 0 that day; days_seen says on how many days it occurred.
-- Stops without an ARS (ferry piers, some virtual stops) are keyed by
-- 'name:<stop name>' in the ARS columns.
CREATE TABLE IF NOT EXISTS public.od_typical_route_stop (
    기준일자              VARCHAR(32)      NOT NULL,
    day_type              VARCHAR(16)      NOT NULL,
    노선명                VARCHAR(255)     NOT NULL,
    전환_노선id           BIGINT,
    승차_정류장순번        INTEGER,
    승차_정류장ars        VARCHAR(255)     NOT NULL,
    승차_정류장표준코드     VARCHAR(255),
    승차_정류장명          VARCHAR(255),
    하차_정류장순번        INTEGER,
    하차_정류장ars        VARCHAR(255)     NOT NULL,
    하차_정류장표준코드     VARCHAR(255),
    하차_정류장명          VARCHAR(255),
    승객수                DOUBLE PRECISION NOT NULL,
    n_days                INTEGER          NOT NULL,
    days_seen             INTEGER          NOT NULL,
    window_start          DATE             NOT NULL,
    window_end            DATE             NOT NULL,
    source                VARCHAR(255)     NOT NULL,
    PRIMARY KEY (기준일자, 노선명, 승차_정류장ars, 하차_정류장ars)
);

CREATE INDEX IF NOT EXISTS idx_od_typical_route_stop_route_seq
ON public.od_typical_route_stop (기준일자, 노선명, 승차_정류장순번);
