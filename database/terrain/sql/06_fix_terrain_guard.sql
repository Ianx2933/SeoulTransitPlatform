-- Repair the publication count check in terrain_guard_snapshot().
-- The original function selected three assignment counts into two variables.
-- Preserve the existing immutability guards and correct the count mapping.
-- Run against the intended Seoul_Transit database.
BEGIN;

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

COMMIT;
