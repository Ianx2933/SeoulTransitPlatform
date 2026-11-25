-- migrations/04_bus_route_stop_snapshot.sql
-- Monthly snapshots of the Seoul route master (route, stop order, positions).
--
-- 2026-11-24: withdrawn routes (1155) and stops outside Seoul had no
-- positions, so a past period could not be drawn. Same table as at the end of
-- 08_bus_route_path.sql, for databases created before it. Safe to run twice.
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
