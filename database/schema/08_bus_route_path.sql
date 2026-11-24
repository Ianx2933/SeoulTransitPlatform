-- 08_bus_route_path.sql
-- Stored bus route paths (transit-bus-route-path Job).
--
-- Seoul bus route geometry from the Seoul bus route API (ws.bus.go.kr
-- busRouteInfo), cut into stop-to-stop segments so that per-segment loads can
-- be summed across routes and drawn along the road.
-- Filled by pipelines/bus_route_path/load_bus_route_path.py; each route is
-- replaced in one transaction, so readers never see half a route.

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
    fetched_at    timestamptz NOT NULL DEFAULT now()
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
