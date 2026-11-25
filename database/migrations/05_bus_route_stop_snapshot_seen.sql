-- migrations/05_bus_route_stop_snapshot_seen.sql
-- First and last snapshot each route appeared in.
--
-- 2026-11-25: bus_route_stop_snapshot keeps only changes, so it cannot tell
-- when a route stopped running (1155). load_snapshot.sql keeps this table up
-- to date from every file it loads. Same table as at the end of
-- 08_bus_route_path.sql, for databases created before it. Safe to run twice.
CREATE TABLE IF NOT EXISTS bus_route_stop_snapshot_seen (
    route_id    text NOT NULL PRIMARY KEY,
    route_nm    text NOT NULL,
    first_seen  date NOT NULL,
    last_seen   date NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bus_route_stop_snapshot_seen_nm ON bus_route_stop_snapshot_seen (route_nm);

-- Routes already loaded: what the kept snapshots show (first_seen exact,
-- last_seen at least the last change). Loading the files again makes last_seen exact.
INSERT INTO bus_route_stop_snapshot_seen (route_id, route_nm, first_seen, last_seen)
SELECT route_id, (array_agg(route_nm ORDER BY stdr_de DESC))[1], min(stdr_de), max(stdr_de)
FROM bus_route_stop_snapshot
GROUP BY route_id
ON CONFLICT (route_id) DO NOTHING;
