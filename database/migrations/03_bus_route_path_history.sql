-- migrations/03_bus_route_path_history.sql
-- Keeps earlier paths of bus routes whose station order changed.
--
-- 2026-11-24: the collector replaced a changed route outright, so a past
-- period of 3411 could not be drawn on the road it then ran. Adds the
-- columns and tables of 08_bus_route_path.sql for databases created before
-- them; new databases get them from 08 directly. Safe to run twice.
ALTER TABLE bus_route_path_route ADD COLUMN IF NOT EXISTS path_since timestamptz;
ALTER TABLE bus_route_path_route ADD COLUMN IF NOT EXISTS path_sig text;
UPDATE bus_route_path_route SET path_since = fetched_at WHERE path_since IS NULL AND status = 'ok';

CREATE TABLE IF NOT EXISTS bus_route_path_version (
    version_id     bigserial PRIMARY KEY,
    route_nm       text        NOT NULL,
    od_route_nm    text        NOT NULL,
    route_id       text,
    start_name     text,
    end_name       text,
    n_stations     integer,
    turnaround_seq integer,
    path_sig       text,
    valid_from     timestamptz,
    valid_to       timestamptz NOT NULL,
    reason         text        NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bus_route_path_version_od ON bus_route_path_version (od_route_nm, valid_to);

CREATE TABLE IF NOT EXISTS bus_route_path_version_station (
    version_id  bigint  NOT NULL REFERENCES bus_route_path_version (version_id) ON DELETE CASCADE,
    seq         integer NOT NULL,
    ars         text,
    station_id  text,
    station_nm  text,
    lng         double precision,
    lat         double precision,
    trans_yn    boolean NOT NULL DEFAULT false,
    PRIMARY KEY (version_id, seq)
);

CREATE TABLE IF NOT EXISTS bus_route_path_version_segment (
    version_id  bigint  NOT NULL REFERENCES bus_route_path_version (version_id) ON DELETE CASCADE,
    seq         integer NOT NULL,
    from_ars    text,
    to_ars      text,
    length_m    double precision NOT NULL,
    geom        geometry(LineString, 4326) NOT NULL,
    PRIMARY KEY (version_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_bus_route_path_version_segment_pair ON bus_route_path_version_segment (from_ars, to_ars);
