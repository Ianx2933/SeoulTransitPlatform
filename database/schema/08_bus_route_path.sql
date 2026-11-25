-- 08_bus_route_path.sql
-- Stored bus route paths (transit-bus-route-path Job).
--
-- Seoul bus route geometry from the Seoul bus route API (ws.bus.go.kr
-- busRouteInfo), cut into stop-to-stop segments so that per-segment loads can
-- be summed across routes and drawn along the road.
-- Filled by pipelines/bus_route_path/load_bus_route_path.py; each route is
-- replaced in one transaction, so readers never see half a route. When a
-- route's station order changes, or a stored route disappears, the rows it
-- replaces are kept in the bus_route_path_version tables at the end.

CREATE TABLE IF NOT EXISTS bus_route_path_route (
    route_nm      text PRIMARY KEY,          -- route name; the API name for alias variants
    od_route_nm   text NOT NULL,             -- route name as in od_typical_route_stop
    route_id      text,                      -- busRouteId
    route_type    integer,
    start_name    text,
    end_name      text,
    n_stations    integer,
    n_points      integer,
    turnaround_seq integer,                  -- first station with transYn = Y
    status        text NOT NULL,             -- ok | not_found | error
    message       text,
    fetched_at    timestamptz NOT NULL DEFAULT now(),
    path_since    timestamptz,               -- first fetch with this station order (bus_route_path_version)
    path_sig      text                       -- station order: ARS (or id:<station_id>) joined by '>'
);
CREATE INDEX IF NOT EXISTS idx_bus_route_path_route_od ON bus_route_path_route (od_route_nm);

CREATE TABLE IF NOT EXISTS bus_route_path_station (
    route_nm    text    NOT NULL REFERENCES bus_route_path_route (route_nm) ON DELETE CASCADE,
    seq         integer NOT NULL,
    ars         text,                        -- 5 digits, NULL for stations without an ARS number
    station_id  text,
    station_nm  text,
    lng         double precision,
    lat         double precision,
    trans_yn    boolean NOT NULL DEFAULT false,
    path_idx    integer NOT NULL,            -- index of the matched path vertex
    PRIMARY KEY (route_nm, seq)
);
CREATE INDEX IF NOT EXISTS idx_bus_route_path_station_ars ON bus_route_path_station (ars);

CREATE TABLE IF NOT EXISTS bus_route_path_segment (
    route_nm  text    NOT NULL REFERENCES bus_route_path_route (route_nm) ON DELETE CASCADE,
    seq       integer NOT NULL,              -- segment from station seq to seq + 1
    from_ars  text,
    to_ars    text,
    length_m  double precision NOT NULL,
    geom      geometry(LineString, 4326) NOT NULL,
    PRIMARY KEY (route_nm, seq)
);
CREATE INDEX IF NOT EXISTS idx_bus_route_path_segment_pair ON bus_route_path_segment (from_ars, to_ars);
CREATE INDEX IF NOT EXISTS idx_bus_route_path_segment_geom ON bus_route_path_segment USING gist (geom);

-- Earlier paths of routes whose station order changed (3411 and the like), so
-- a past period can be drawn on the road it then ran. One version per replaced
-- route row; valid_from is the first fetch that saw it (for rows stored before
-- path_since existed, the last fetch before the history began).
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
    valid_to       timestamptz NOT NULL,     -- the fetch that found the change
    reason         text        NOT NULL      -- changed | gone
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

-- Monthly snapshots of the Seoul route master (stops each route served, with
-- positions), from the files of the Seoul route-stop dataset. Gives positions
-- of stops outside Seoul and of stops on withdrawn routes (1155), which the
-- stop tables lack. Loaded by pipelines/bus_route_stop_snapshot.
CREATE TABLE IF NOT EXISTS bus_route_stop_snapshot (
    stdr_de     date             NOT NULL,   -- snapshot date (STDR_DE)
    route_id    text             NOT NULL,
    route_nm    text             NOT NULL,
    seq         integer          NOT NULL,   -- STTN_SN
    node_id     text,
    ars         text,                        -- STTN_NO padded to five digits; NULL for "0" (no number)
    node_nm     text,
    lng         double precision NOT NULL,
    lat         double precision NOT NULL,
    PRIMARY KEY (stdr_de, route_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_bus_route_stop_snapshot_ars ON bus_route_stop_snapshot (ars, stdr_de);
CREATE INDEX IF NOT EXISTS idx_bus_route_stop_snapshot_route ON bus_route_stop_snapshot (route_nm, stdr_de);

-- First and last snapshot each route appeared in (the snapshots above keep
-- only changes, so they cannot say when a route stopped running).
CREATE TABLE IF NOT EXISTS bus_route_stop_snapshot_seen (
    route_id    text NOT NULL PRIMARY KEY,
    route_nm    text NOT NULL,
    first_seen  date NOT NULL,
    last_seen   date NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bus_route_stop_snapshot_seen_nm ON bus_route_stop_snapshot_seen (route_nm);
