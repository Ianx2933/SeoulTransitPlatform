-- Retire the terrain columns on the stop master, AFTER a snapshot is published
-- and active. Run nothing here until 03_terrain_diagnostics.sql looks correct.
--
-- Why: bus_stop_location is reloaded from CSV by the reference loader. Any
-- derived value stored there is destroyed by the next --replace run, silently.
--
-- Usage: psql -X -v ON_ERROR_STOP=1 -f sql/05_retire_legacy_columns.sql

\set ON_ERROR_STOP on
BEGIN;

-- Step 1. Refuse to proceed unless an active snapshot reproduces the master.
DO $$
DECLARE
    active_id text;
    master_measured bigint;
    snapshot_measured bigint;
    mismatched bigint;
BEGIN
    SELECT dataset_id INTO active_id FROM public.terrain_active_dataset WHERE singleton;
    IF active_id IS NULL THEN
        RAISE EXCEPTION 'No active terrain dataset; publish and activate one first';
    END IF;

    SELECT COUNT(*) FILTER (WHERE slope_pct IS NOT NULL)
    INTO master_measured FROM public.bus_stop_location;

    SELECT COUNT(*) FILTER (WHERE slope_pct IS NOT NULL)
    INTO snapshot_measured FROM public.bus_stop_terrain_stats WHERE dataset_id = active_id;

    IF master_measured <> snapshot_measured THEN
        RAISE EXCEPTION 'Measured counts differ: master=%, snapshot=%',
            master_measured, snapshot_measured;
    END IF;

    -- Compare values per stop, not just totals. IS DISTINCT FROM treats NULL
    -- as a value, so a missing reading on one side is caught too.
    SELECT COUNT(*) INTO mismatched
    FROM public.bus_stop_location b
    JOIN public.bus_stop_terrain_stats s
      ON s.dataset_id = active_id AND s.node_id = b."노드id"::text
    WHERE b.slope_pct IS DISTINCT FROM s.slope_pct
       OR b.elev_m    IS DISTINCT FROM s.elev_m;

    IF mismatched > 0 THEN
        RAISE EXCEPTION '% stops disagree between master and snapshot', mismatched;
    END IF;

    RAISE NOTICE 'Verified against dataset %: % measured stops match',
        active_id, snapshot_measured;
END $$;

-- Step 2. Drop the derived columns. The snapshot is now the only home for them.
ALTER TABLE public.bus_stop_location
    DROP COLUMN IF EXISTS elev_m,
    DROP COLUMN IF EXISTS slope_pct;

COMMIT;

-- After this migration, publish with --csv from the raster pipeline output.
-- --from-master no longer has anything to read.
