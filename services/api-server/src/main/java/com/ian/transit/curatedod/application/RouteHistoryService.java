package com.ian.transit.curatedod.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ian.transit.curatedod.infrastructure.RouteSnapshotRepository;
import com.ian.transit.curatedod.infrastructure.RouteSnapshotRepository.Seen;
import com.ian.transit.curatedod.infrastructure.RouteSnapshotRepository.VersionRow;
import com.ian.transit.curatedod.infrastructure.StoredRoutePathRepository;
import com.ian.transit.curatedod.infrastructure.StoredRoutePathRepository.StationRow;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * When a route changed, from the Seoul route master snapshots
 * (bus_route_stop_snapshot keeps one snapshot per version): the stops added
 * and removed at each change, when the route was last seen if it no longer
 * runs, and whether today's stored path differs from the newest snapshot.
 * Snapshots are about monthly, so a change is dated "between the snapshot
 * before and this one"; with gaps in the files, that span is wide.
 */
@Service
public class RouteHistoryService {

    private final RouteSnapshotRepository snapshots;
    private final StoredRoutePathRepository paths;
    private final Cache<String, History> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(12))
            .build();

    public RouteHistoryService(RouteSnapshotRepository snapshots, StoredRoutePathRepository paths) {
        this.snapshots = snapshots;
        this.paths = paths;
    }

    public record StopRef(String ars, String name) {
    }

    /**
     * kind: first (the route's first snapshot), changed, ended (last seen; not
     * in later snapshots), today (today's stored path differs from the newest
     * snapshot). since: the snapshot before, so the change happened after it.
     */
    public record Entry(String kind, LocalDate date, LocalDate since, int stops,
                        List<StopRef> added, List<StopRef> removed, boolean reordered) {
    }

    public record History(String route, List<Entry> entries, LocalDate firstSeen, LocalDate lastSeen,
                          LocalDate latestSnapshot) {
    }

    public History history(String route) {
        String name = route == null ? "" : route.trim();
        if (name.isEmpty() || name.length() > 30) {
            throw new IllegalArgumentException("give a route name");
        }
        return cache.get(name, k -> {
            var names = StopPathService.routeNames(name);
            List<StationRow> today;
            try {
                today = paths.stations(name);
            } catch (RuntimeException e) {
                today = List.of();
            }
            return build(name, snapshots.versions(names), snapshots.seen(names), today);
        });
    }

    /** Pure: versions in date order, one route variant per date (the one with most stops). */
    static History build(String route, List<VersionRow> rows, Seen seen, List<StationRow> today) {
        Map<LocalDate, Map<String, List<VersionRow>>> byDate = new TreeMap<>();
        for (VersionRow r : rows) {
            byDate.computeIfAbsent(r.date(), d -> new LinkedHashMap<>())
                    .computeIfAbsent(r.routeId(), id -> new ArrayList<>()).add(r);
        }
        List<Entry> entries = new ArrayList<>();
        List<StopRef> previous = null;
        LocalDate previousDate = null;
        for (var e : byDate.entrySet()) {
            List<StopRef> stops = e.getValue().values().stream()
                    .max(Comparator.comparingInt(List::size)).orElse(List.of()).stream()
                    .sorted(Comparator.comparingInt(VersionRow::seq))
                    .map(r -> new StopRef(r.ars(), r.name())).toList();
            if (previous == null) {
                entries.add(new Entry("first", e.getKey(), null, stops.size(), List.of(), List.of(), false));
            } else {
                Entry change = diff("changed", e.getKey(), previousDate, previous, stops);
                if (change != null) {
                    entries.add(change);
                }
            }
            previous = stops;
            previousDate = e.getKey();
        }
        if (seen != null && seen.latest() != null && seen.last().isBefore(seen.latest())) {
            entries.add(new Entry("ended", seen.last(), null, 0, List.of(), List.of(), false));
        } else if (previous != null && !today.isEmpty()) {
            List<StopRef> current = bestVariant(today, previous);
            Entry change = diff("today", null, previousDate, previous, current);
            if (change != null) {
                entries.add(change);
            }
        }
        return new History(route, entries, seen == null ? null : seen.first(), seen == null ? null : seen.last(),
                seen == null ? null : seen.latest());
    }

    /** Today's stored variant closest to the snapshot (a route can be stored as several API routes). */
    static List<StopRef> bestVariant(List<StationRow> today, List<StopRef> like) {
        var want = like.stream().map(StopRef::ars).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, List<StationRow>> byVariant = today.stream()
                .collect(Collectors.groupingBy(StationRow::variant, LinkedHashMap::new, Collectors.toList()));
        return byVariant.values().stream()
                .max(Comparator.comparingLong(v -> v.stream().map(StationRow::ars).filter(want::contains).count()))
                .orElse(List.of()).stream()
                .sorted(Comparator.comparingInt(StationRow::seq))
                .map(s -> new StopRef(s.ars(), s.name())).toList();
    }

    /** Null when the stop order is the same. */
    static Entry diff(String kind, LocalDate date, LocalDate since, List<StopRef> before, List<StopRef> after) {
        List<String> a = before.stream().map(StopPathService::arsKey).toList();
        List<String> b = after.stream().map(StopPathService::arsKey).toList();
        if (a.equals(b)) {
            return null;
        }
        var inA = new LinkedHashSet<>(a);
        var inB = new LinkedHashSet<>(b);
        List<StopRef> added = distinct(after.stream().filter(s -> !inA.contains(StopPathService.arsKey(s))).toList());
        List<StopRef> removed = distinct(before.stream().filter(s -> !inB.contains(StopPathService.arsKey(s))).toList());
        return new Entry(kind, date, since, after.size(), added, removed, added.isEmpty() && removed.isEmpty());
    }

    private static List<StopRef> distinct(List<StopRef> stops) {
        Map<String, StopRef> out = new LinkedHashMap<>();
        stops.forEach(s -> out.putIfAbsent(StopPathService.arsKey(s), s));
        return List.copyOf(out.values());
    }
}
