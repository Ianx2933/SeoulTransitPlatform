-- Additive migration. Never run this through a loader's --replace option.
-- Run with psql -X -v ON_ERROR_STOP=1 -f sql/01_terrain_schema.sql.
BEGIN;
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE IF NOT EXISTS public.terrain_dataset (
    dataset_id text PRIMARY KEY CHECK (length(dataset_id) BETWEEN 1 AND 160),
    source_id text NOT NULL CHECK (length(btrim(source_id)) > 0),
    slope_method text NOT NULL CHECK (length(btrim(slope_method)) > 0),
    boundary_base_date varchar(8) NOT NULL CHECK (boundary_base_date ~ '^[0-9]{8}$'),
    processed_at timestamptz NOT NULL,
    published_at timestamptz,
    stop_count bigint NOT NULL DEFAULT 0 CHECK (stop_count >= 0),
    measured_stop_count bigint NOT NULL DEFAULT 0 CHECK (measured_stop_count >= 0),
    assigned_stop_count bigint NOT NULL DEFAULT 0 CHECK (assigned_stop_count >= 0),
    ambiguous_boundary_stop_count bigint NOT NULL DEFAULT 0 CHECK (ambiguous_boundary_stop_count >= 0),
    CHECK (measured_stop_count <= stop_count),
    CHECK (assigned_stop_count <= stop_count),
    CHECK (ambiguous_boundary_stop_count <= assigned_stop_count)
);

CREATE TABLE IF NOT EXISTS public.bus_stop_terrain_stats (
    dataset_id text NOT NULL REFERENCES public.terrain_dataset(dataset_id) ON DELETE RESTRICT,
    node_id text NOT NULL CHECK (length(node_id) > 0),
    stop_no text,
    stop_name text NOT NULL,
    geom geometry(Point,4326) NOT NULL,
    elev_m double precision,
    slope_pct double precision,
    PRIMARY KEY (dataset_id, node_id),
    CHECK (NOT ST_IsEmpty(geom) AND ST_IsValid(geom)),
    CHECK (ST_X(geom) BETWEEN -180 AND 180 AND ST_Y(geom) BETWEEN -90 AND 90),
    CHECK (elev_m IS NULL OR (elev_m > '-Infinity'::float8 AND elev_m < 'Infinity'::float8)),
    CHECK (slope_pct IS NULL OR (slope_pct >= 0 AND slope_pct < 'Infinity'::float8))
);

CREATE TABLE IF NOT EXISTS public.terrain_stop_dong_assignment (
    dataset_id text NOT NULL,
    node_id text NOT NULL,
    adm_cd text,
    adm_nm text,
    assignment_method text NOT NULL CHECK (assignment_method IN ('interior','boundary','unmatched')),
    candidate_count integer NOT NULL CHECK (candidate_count >= 0),
    PRIMARY KEY (dataset_id,node_id),
    FOREIGN KEY (dataset_id,node_id)
        REFERENCES public.bus_stop_terrain_stats(dataset_id,node_id) ON DELETE RESTRICT,
    CHECK ((adm_cd IS NULL AND adm_nm IS NULL AND assignment_method = 'unmatched' AND candidate_count = 0)
        OR (adm_cd IS NOT NULL AND adm_nm IS NOT NULL AND assignment_method <> 'unmatched' AND candidate_count >= 1))
);

CREATE TABLE IF NOT EXISTS public.terrain_active_dataset (
    singleton boolean PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    dataset_id text NOT NULL REFERENCES public.terrain_dataset(dataset_id) ON DELETE RESTRICT,
    activated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_terrain_stats_slope
    ON public.bus_stop_terrain_stats(dataset_id, slope_pct DESC, node_id)
    WHERE slope_pct IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_terrain_assignment_dong
    ON public.terrain_stop_dong_assignment(dataset_id,adm_cd,node_id)
    WHERE adm_cd IS NOT NULL;

-- Published snapshots cannot be edited, deleted or supplemented. The parent
-- row lock also serializes publication with writes to its child rows.
CREATE OR REPLACE FUNCTION public.terrain_guard_snapshot()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    published timestamptz;
    actual_stops bigint;
    actual_measured bigint;
    actual_assignments bigint;
    actual_assigned bigint;
    actual_ambiguous bigint;
BEGIN
    IF TG_TABLE_NAME = 'terrain_dataset' THEN
        IF TG_OP = 'DELETE' THEN
            IF OLD.published_at IS NOT NULL THEN
                RAISE EXCEPTION 'Published terrain datasets are immutable';
            END IF;
            RETURN OLD;
        END IF;

        IF OLD.published_at IS NOT NULL THEN
            RAISE EXCEPTION 'Published terrain datasets are immutable';
        END IF;

        IF (NEW.dataset_id, NEW.source_id, NEW.slope_method,
            NEW.boundary_base_date, NEW.processed_at)
           IS DISTINCT FROM
           (OLD.dataset_id, OLD.source_id, OLD.slope_method,
            OLD.boundary_base_date, OLD.processed_at) THEN
            RAISE EXCEPTION 'Terrain dataset identity and provenance cannot change';
        END IF;

        IF NEW.published_at IS NOT NULL THEN
            SELECT COUNT(*), COUNT(slope_pct)
            INTO actual_stops, actual_measured
            FROM public.bus_stop_terrain_stats
            WHERE dataset_id = NEW.dataset_id;

            SELECT COUNT(*),
                   COUNT(*) FILTER (WHERE adm_cd IS NOT NULL),
                   COUNT(*) FILTER (WHERE candidate_count > 1)
            INTO actual_assignments, actual_assigned, actual_ambiguous
            FROM public.terrain_stop_dong_assignment
            WHERE dataset_id = NEW.dataset_id;

            IF (NEW.stop_count, NEW.measured_stop_count,
                NEW.assigned_stop_count, NEW.ambiguous_boundary_stop_count)
               IS DISTINCT FROM
               (actual_stops, actual_measured,
                actual_assigned, actual_ambiguous)
               OR actual_assignments <> actual_stops THEN
                RAISE EXCEPTION
                    'Terrain publication counts or assignment cardinality do not match';
            END IF;
        END IF;

        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT published_at INTO published
        FROM public.terrain_dataset
        WHERE dataset_id = NEW.dataset_id
        FOR UPDATE;

        IF published IS NOT NULL THEN
            RAISE EXCEPTION 'Published terrain snapshots are immutable';
        END IF;
        RETURN NEW;
    END IF;

    SELECT published_at INTO published
    FROM public.terrain_dataset
    WHERE dataset_id = OLD.dataset_id
    FOR UPDATE;

    IF published IS NOT NULL THEN
        RAISE EXCEPTION 'Published terrain snapshots are immutable';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;

    IF NEW.dataset_id IS DISTINCT FROM OLD.dataset_id THEN
        RAISE EXCEPTION 'Move between terrain datasets is not permitted';
    END IF;

    RETURN NEW;
END $$;

DROP TRIGGER IF EXISTS terrain_dataset_guard ON public.terrain_dataset;
CREATE TRIGGER terrain_dataset_guard BEFORE UPDATE OR DELETE ON public.terrain_dataset
FOR EACH ROW EXECUTE FUNCTION public.terrain_guard_snapshot();
DROP TRIGGER IF EXISTS terrain_stats_guard ON public.bus_stop_terrain_stats;
CREATE TRIGGER terrain_stats_guard BEFORE INSERT OR UPDATE OR DELETE ON public.bus_stop_terrain_stats
FOR EACH ROW EXECUTE FUNCTION public.terrain_guard_snapshot();
DROP TRIGGER IF EXISTS terrain_assignment_guard ON public.terrain_stop_dong_assignment;
CREATE TRIGGER terrain_assignment_guard BEFORE INSERT OR UPDATE OR DELETE ON public.terrain_stop_dong_assignment
FOR EACH ROW EXECUTE FUNCTION public.terrain_guard_snapshot();

CREATE OR REPLACE FUNCTION public.terrain_guard_activation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.terrain_dataset
                   WHERE dataset_id = NEW.dataset_id AND published_at IS NOT NULL) THEN
        RAISE EXCEPTION 'Cannot activate an unpublished terrain dataset';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS terrain_activation_guard ON public.terrain_active_dataset;
CREATE TRIGGER terrain_activation_guard BEFORE INSERT OR UPDATE ON public.terrain_active_dataset
FOR EACH ROW EXECUTE FUNCTION public.terrain_guard_activation();
COMMIT;
