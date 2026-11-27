package com.ian.transit.map.month;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.repository.support.DemandSqlSupport;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The demand map for one month, bus only (BusMonthDemandRepository): the same
 * day types, combination (mean per chosen day or sum), hours and routes as
 * the typical map. The subway has no monthly hourly data yet.
 */
@Service
public class BusMonthDemandService {

    static final List<String> DAY_TYPES = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun_holiday");
    static final int MAX_ROUTES = 200;

    private final BusMonthDemandRepository repository;
    private final Cache<String, List<MapDemandResponse>> cache = Caffeine.newBuilder()
            .maximumSize(300).expireAfterWrite(Duration.ofHours(12)).build();
    private final Cache<String, List<String>> monthCache = Caffeine.newBuilder()
            .maximumSize(1).expireAfterWrite(Duration.ofHours(12)).build();

    public BusMonthDemandService(BusMonthDemandRepository repository) {
        this.repository = repository;
    }

    /** "2024-01".."2026-10": months the map can show for buses. */
    public List<String> months() {
        return monthCache.get("all", k -> repository.months().stream()
                .map(ym -> String.format("%04d-%02d", ym / 100, ym % 100)).toList());
    }

    public List<MapDemandResponse> demand(String month, String dayTypes, String dayAggregation, String hours,
                                          String lines) {
        int ym = parseMonth(month);
        List<String> days = parseList(dayTypes, "dayTypes");
        for (String d : days) {
            if (!DAY_TYPES.contains(d)) {
                throw new IllegalArgumentException("unsupported dayType: " + d);
            }
        }
        if (!"average".equals(dayAggregation) && !"sum".equals(dayAggregation)) {
            throw new IllegalArgumentException("dayAggregation must be average or sum");
        }
        List<Integer> hourList = new ArrayList<>();
        for (String h : parseList(hours, "hours")) {
            int v;
            try {
                v = Integer.parseInt(h);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("hours must be numbers 0 to 23");
            }
            if (v < 0 || v > 23) {
                throw new IllegalArgumentException("hour must be between 0 and 23");
            }
            hourList.add(v);
        }
        List<String> routes = parseList(lines, "lines");
        if (routes.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("at most " + MAX_ROUTES + " routes for a month");
        }
        String key = ym + "|" + new TreeSet<>(days) + "|" + dayAggregation + "|" + new TreeSet<>(hourList)
                + "|" + new TreeSet<>(routes);
        return cache.get(key, k -> load(ym, days, dayAggregation, hourList, routes, month));
    }

    private List<MapDemandResponse> load(int ym, List<String> days, String aggregation, List<Integer> hours,
                                         List<String> routes, String month) {
        List<BusMonthDemandRepository.StopMonthDemand> rows = repository.demand(ym, days, hours, routes);
        if (rows.isEmpty()) {
            throw new NotFoundException("No bus boardings for the chosen routes in " + month);
        }
        int divisor = DemandSqlSupport.getDayAggregationDivisor(days, aggregation);
        Map<String, BusMonthDemandRepository.StopPoint> points = repository.stopPoints(
                rows.stream().map(BusMonthDemandRepository.StopMonthDemand::ars).distinct().toList());
        // A route's own stop wins over the Seoul stop of the same number (안양, 성남 share 50 numbers).
        Map<String, BusMonthDemandRepository.StopPoint> away = repository.awayPoints();
        List<MapDemandResponse> out = new ArrayList<>();
        for (BusMonthDemandRepository.StopMonthDemand r : rows) {
            BusMonthDemandRepository.StopPoint p = away.getOrDefault(r.route() + "|" + r.ars(), points.get(r.ars()));
            if (p == null) {
                continue;
            }
            out.add(new MapDemandResponse("bus", r.route(), r.ars(), p.name(), p.lat(), p.lng(),
                    Math.round(r.boarding() / divisor), Math.round(r.alighting() / divisor)));
        }
        out.sort(Comparator.comparing(MapDemandResponse::serviceId).thenComparing(MapDemandResponse::nodeName,
                Comparator.nullsLast(Comparator.<String>naturalOrder())));
        return out;
    }

    static int parseMonth(String month) {
        if (month == null || month.isBlank()) {
            throw new IllegalArgumentException("month is required, e.g. month=2026-03");
        }
        try {
            YearMonth m = YearMonth.parse(month.trim());
            return m.getYear() * 100 + m.getMonthValue();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be YYYY-MM, got " + month);
        }
    }

    private static List<String> parseList(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        List<String> out = Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .distinct().toList();
        if (out.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return out;
    }
}
