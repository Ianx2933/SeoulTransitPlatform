-- pipelines/bus_route_stop_snapshot/load_snapshot.sql
-- Loads bus_route_stop_snapshot_clean.csv (from clean_snapshot.py, in the
-- folder psql runs from) into bus_route_stop_snapshot. Snapshot dates in the
-- file replace the same dates in the table; other dates are kept.
--
-- Only changes are kept: a route's snapshot with the same stops in the same
-- order as its previous snapshot is dropped, so each route keeps one snapshot
-- per version (the first date it was seen that way). A month of the master is
-- about 41,000 rows; kept whole, 30 months would be some 400 MB.
--
-- Run with: psql ... -v ON_ERROR_STOP=1 -1 -f pipelines/bus_route_stop_snapshot/load_snapshot.sql
\encoding UTF8
CREATE TEMP TABLE snapshot_in (LIKE bus_route_stop_snapshot) ON COMMIT DROP;
\copy snapshot_in (stdr_de, route_id, route_nm, seq, node_id, ars, node_nm, lng, lat) FROM 'bus_route_stop_snapshot_clean.csv' WITH (FORMAT csv, HEADER true)
DELETE FROM bus_route_stop_snapshot WHERE stdr_de IN (SELECT DISTINCT stdr_de FROM snapshot_in);
INSERT INTO bus_route_stop_snapshot SELECT * FROM snapshot_in;

-- Drop route snapshots identical to the same route's previous one.
WITH sig AS (
    SELECT stdr_de, route_id,
           string_agg(COALESCE(ars, '-') || ':' || COALESCE(node_id, '-'), '>' ORDER BY seq) AS s
    FROM bus_route_stop_snapshot GROUP BY stdr_de, route_id
), dup AS (
    SELECT stdr_de, route_id FROM (
        SELECT stdr_de, route_id, s, lag(s) OVER (PARTITION BY route_id ORDER BY stdr_de) AS prev FROM sig
    ) t WHERE s = prev
)
DELETE FROM bus_route_stop_snapshot b USING dup
WHERE b.stdr_de = dup.stdr_de AND b.route_id = dup.route_id;

SELECT stdr_de, count(*) AS rows, count(DISTINCT route_nm) AS routes_changed_or_new
FROM bus_route_stop_snapshot GROUP BY stdr_de ORDER BY stdr_de;
