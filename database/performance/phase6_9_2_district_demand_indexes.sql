-- Phase 6.9-2 District Demand Performance Indexes
-- (Phase 6.9-2 행정동 수요 API 성능 개선용 인덱스)
--
-- Purpose:
-- - Reduce request-time cost for district-demand spatial/demand joins.
-- - Make the DB optimization reproducible through a checked-in SQL file.
--
-- 목적:
-- - district-demand API의 공간 조인/수요 조인 비용을 낮춥니다.
-- - pgAdmin/psql에서 직접 적용한 DB 변경을 Git으로 재현 가능하게 남깁니다.
--
-- Measured result in local development:
-- - Before indexes/ANALYZE: approximately 9.5s to 12.9s
-- - After indexes/ANALYZE, cold call: approximately 192ms
--
-- 로컬 개발 환경 측정 결과:
-- - 인덱스/ANALYZE 전: 약 9.5초 ~ 12.9초
-- - 인덱스/ANALYZE 후 cold call: 약 192ms
--
-- Usage:
--   psql -h localhost -p 5432 -U postgres -d Seoul_Transit -f database/performance/phase6_9_2_district_demand_indexes.sql
--
-- 사용법:
--   psql -h localhost -p 5432 -U postgres -d Seoul_Transit -f database/performance/phase6_9_2_district_demand_indexes.sql

-- Spatial index for admin-dong boundary lookup.
-- (행정동 경계 polygon 조회용 공간 인덱스)
CREATE INDEX IF NOT EXISTS idx_admin_dong_boundary_geom
ON public.admin_dong_boundary
USING GIST (geom);

-- Spatial index for original bus stop geometry.
-- (원본 버스 정류장 geometry 조회용 공간 인덱스)
CREATE INDEX IF NOT EXISTS idx_bus_stop_location_geom
ON public.bus_stop_location
USING GIST (geom);

-- Spatial index for integrated bus stop geometry.
-- (통합 버스 정류장 geometry 조회용 공간 인덱스)
CREATE INDEX IF NOT EXISTS idx_integrated_bus_stop_location_geom
ON public.integrated_bus_stop_location
USING GIST (geom);

-- Composite demand filter/join index for mode, day type, hour, service, and node.
-- (mode/day_type/hour/service_id/node_id 조건 및 조인용 복합 인덱스)
CREATE INDEX IF NOT EXISTS idx_ihtdl_mode_day_hour_service_node
ON public.integrated_hourly_transit_demand_light
(mode, day_type, hour, service_id, node_id);

-- Normalized bus node id expression index for 5-digit stop ids.
-- (5자리 버스 정류장 번호 비교용 정규화 expression 인덱스)
CREATE INDEX IF NOT EXISTS idx_ihtdl_bus_norm_node_id
ON public.integrated_hourly_transit_demand_light
((LPAD(node_id::text, 5, '0')))
WHERE mode = 'bus';

-- Normalized original bus stop id expression index.
-- (원본 버스 정류장 번호 정규화 expression 인덱스)
CREATE INDEX IF NOT EXISTS idx_bus_stop_location_norm_stop_id
ON public.bus_stop_location
((LPAD("정류장번호"::text, 5, '0')));

-- Mapping table join index for service id and normalized demand node id.
-- (service_id + demand_node_id 정규화 조인용 mapping table 인덱스)
CREATE INDEX IF NOT EXISTS idx_bus_stop_demand_node_mapping_key
ON public.bus_stop_demand_node_mapping
(service_id, (LPAD(demand_node_id::text, 5, '0')));

-- Canonical node lookup index for integrated bus stop location.
-- (통합 버스 정류장의 canonical_node_id 조회용 인덱스)
CREATE INDEX IF NOT EXISTS idx_integrated_bus_stop_location_canonical
ON public.integrated_bus_stop_location
(canonical_node_id);

-- Refresh planner statistics after creating or confirming indexes.
-- (인덱스 생성/확인 후 PostgreSQL planner 통계를 갱신합니다.)
ANALYZE public.admin_dong_boundary;
ANALYZE public.bus_stop_location;
ANALYZE public.integrated_bus_stop_location;
ANALYZE public.integrated_hourly_transit_demand_light;
ANALYZE public.bus_stop_demand_node_mapping;
