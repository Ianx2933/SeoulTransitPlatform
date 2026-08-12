-- 04_reference_admin_dong_boundary.sql
-- Administrative dong boundary polygons.
--
-- This table has no loader in the repository. It was created by importing the
-- SGIS administrative boundary shapefile during development.
--
-- Source shapefile: BND_ADM_DONG_PG/BND_ADM_DONG_PG.shp
--   CRS      : EPSG:5186 (KGD2002 Central Belt 2010)
--   Fields   : BASE_DATE (char 8), ADM_CD (char 8), ADM_NM (char 254)
--
-- Columns consumed by DistrictDemandRepository: adm_cd, adm_nm, geom.
-- adm_cd is the 8-digit code used as the districtCode API parameter
-- (example: 11230760).

CREATE TABLE IF NOT EXISTS public.admin_dong_boundary (
    adm_cd     VARCHAR(10) PRIMARY KEY,
    adm_nm     VARCHAR(254),
    base_date  VARCHAR(8),
    geom       geometry(MultiPolygon, 4326)
);

-- ---------------------------------------------------------------------------
-- Loading options
-- ---------------------------------------------------------------------------
--
-- Option A: shp2pgsql, reprojecting EPSG:5186 -> EPSG:4326.
--
--   shp2pgsql -s 5186:4326 -I -W UTF-8 -g geom \
--     BND_ADM_DONG_PG/BND_ADM_DONG_PG.shp public.admin_dong_boundary_import \
--     | psql -h localhost -p 5432 -U postgres -d Seoul_Transit
--
--   INSERT INTO public.admin_dong_boundary (adm_cd, adm_nm, base_date, geom)
--   SELECT adm_cd, adm_nm, base_date, ST_Multi(geom)
--   FROM public.admin_dong_boundary_import
--   ON CONFLICT (adm_cd) DO UPDATE SET
--       adm_nm    = EXCLUDED.adm_nm,
--       base_date = EXCLUDED.base_date,
--       geom      = EXCLUDED.geom;
--
--   DROP TABLE public.admin_dong_boundary_import;
--
-- Option B: ogr2ogr from the already-reprojected GeoJSON in the repository.
--
--   ogr2ogr -f PostgreSQL \
--     PG:"host=localhost port=5432 dbname=Seoul_Transit user=postgres" \
--     data/geo/capital_area_admin_dong_4326.geojson \
--     -nln public.admin_dong_boundary_import -lco GEOMETRY_NAME=geom -nlt MULTIPOLYGON
--
--   then the same INSERT ... ON CONFLICT as above.
--
-- Note: the same GeoJSON is also served to the frontend from
-- services/web-client/public/data/capital_area_admin_dong_4326.geojson.
