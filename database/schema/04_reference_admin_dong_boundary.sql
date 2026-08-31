-- 04_reference_admin_dong_boundary.sql
-- Administrative dong boundary polygons.
--
-- Structure verified against the live database.
--
-- This table was created by importing the SGIS boundary data with ogr2ogr,
-- which is why it has an ogc_fid serial primary key and unbounded varchar
-- columns rather than hand-written types. The definition below reproduces
-- that shape so a fresh database matches the working one.
--
-- Source shapefile: BND_ADM_DONG_PG/BND_ADM_DONG_PG.shp
--   CRS      : EPSG:5186 (KGD2002 Central Belt 2010)
--   Fields   : BASE_DATE (char 8), ADM_CD (char 8), ADM_NM (char 254)
--
-- Columns consumed by DistrictDemandRepository: adm_cd, adm_nm, geom.
-- adm_cd is the 8-digit code used as the districtCode API parameter
-- (example: 11230760).

CREATE TABLE IF NOT EXISTS public.admin_dong_boundary (
    ogc_fid    SERIAL PRIMARY KEY,
    base_date  VARCHAR,
    adm_cd     VARCHAR,
    adm_nm     VARCHAR,
    geom       geometry(MultiPolygon, 4326)
);

-- adm_cd carries no unique constraint, matching the live database. In the
-- current dataset the values happen to be unique (1,208 rows, 1,208 distinct
-- codes), but nothing enforces that. If a future boundary import introduces a
-- duplicate code, district-demand would count the same node once per matching
-- polygon. Worth re-checking after any boundary reload:
--
--   SELECT COUNT(*), COUNT(DISTINCT adm_cd) FROM public.admin_dong_boundary;

CREATE INDEX IF NOT EXISTS idx_admin_dong_boundary_adm_cd
ON public.admin_dong_boundary (adm_cd);

-- Spatial index.
--
-- ogr2ogr creates its own GIST index named admin_dong_boundary_geom_geom_idx.
-- Two GIST indexes on the same column are redundant: both are maintained on
-- every write while the planner uses only one. If you import with ogr2ogr,
-- drop one of them afterwards.
--
CREATE INDEX IF NOT EXISTS idx_admin_dong_boundary_geom
ON public.admin_dong_boundary USING GIST (geom);

-- ---------------------------------------------------------------------------
-- Loading options
-- ---------------------------------------------------------------------------
--
-- Option A: ogr2ogr from the reprojected GeoJSON. This is how the live table
-- was built, so it reproduces the structure above exactly.
--
--   ogr2ogr -f PostgreSQL \
--     PG:"host=localhost port=5432 dbname=Seoul_Transit user=postgres" \
--     data/geo/capital_area_admin_dong_4326.geojson \
--     -nln public.admin_dong_boundary \
--     -lco GEOMETRY_NAME=geom -nlt MULTIPOLYGON -overwrite
--
-- Option B: shp2pgsql directly from the shapefile, reprojecting 5186 -> 4326.
-- Note that shp2pgsql names its serial key gid, not ogc_fid, so the resulting
-- table differs slightly from the live one.
--
--   shp2pgsql -s 5186:4326 -I -W UTF-8 -g geom \
--     BND_ADM_DONG_PG/BND_ADM_DONG_PG.shp public.admin_dong_boundary \
--     | psql -h localhost -p 5432 -U postgres -d Seoul_Transit
--
-- The frontend serves a separate display-simplified derivative from
-- services/web-client/public/data/capital_area_admin_dong_4326.geojson.
-- Analytical PostGIS loading should use the unsimplified source boundary data.

-- Sanity check after loading.
--   SELECT adm_cd, adm_nm FROM public.admin_dong_boundary WHERE adm_cd = '11230760';
