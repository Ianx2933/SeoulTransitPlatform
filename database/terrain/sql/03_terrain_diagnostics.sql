-- Read-only diagnostics for the current immutable snapshot.
SELECT d.*, a.activated_at FROM public.terrain_active_dataset a
JOIN public.terrain_dataset d ON d.dataset_id=a.dataset_id;
SELECT a.assignment_method, COUNT(*) AS stops,
       COUNT(*) FILTER (WHERE a.candidate_count > 1) AS ambiguous
FROM public.terrain_stop_dong_assignment a
JOIN public.terrain_active_dataset current ON current.dataset_id=a.dataset_id
GROUP BY a.assignment_method ORDER BY a.assignment_method;
SELECT COUNT(*) AS total, COUNT(s.slope_pct) AS measured,
       COUNT(*) FILTER (WHERE s.slope_pct >= 8) AS at_least_eight
FROM public.bus_stop_terrain_stats s
JOIN public.terrain_active_dataset a ON a.dataset_id=s.dataset_id;
-- One assignment per source stop, including unmatched stops.
SELECT s.dataset_id, COUNT(*) AS stats_rows, COUNT(a.node_id) AS assignments
FROM public.bus_stop_terrain_stats s
LEFT JOIN public.terrain_stop_dong_assignment a
 ON a.dataset_id=s.dataset_id AND a.node_id=s.node_id
GROUP BY s.dataset_id;
