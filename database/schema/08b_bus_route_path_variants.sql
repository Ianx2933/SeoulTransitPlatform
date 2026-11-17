-- 2026-11-17: one TOPIS route can be stored as several API routes (the Han
-- River boat runs as three). Adds od_route_nm for databases created with the
-- first 08_bus_route_path.sql; new databases get it from 08 directly.
ALTER TABLE bus_route_path_route ADD COLUMN IF NOT EXISTS od_route_nm text;
UPDATE bus_route_path_route SET od_route_nm = route_nm WHERE od_route_nm IS NULL;
ALTER TABLE bus_route_path_route ALTER COLUMN od_route_nm SET NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bus_route_path_route_od ON bus_route_path_route (od_route_nm);
