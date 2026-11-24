package com.ian.transit.period.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ian.transit.period.Hours;
import com.ian.transit.period.OdBasis;
import com.ian.transit.period.Period;
import com.ian.transit.period.infrastructure.PeriodOdRepository;
import com.ian.transit.period.infrastructure.PeriodOdRepository.PeriodDay;
import com.ian.transit.period.infrastructure.PeriodOdRepository.PeriodLeg;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Period OD and hourly profiles for the route analysis and road views, cached
 * in this instance (Caffeine) so the second identical request does not reach
 * BigQuery: legs for an hour, profiles for a day (they change monthly), the
 * data range for a day.
 *
 * Hours are applied here, after the legs are loaded (typical day from Cloud SQL
 * or a period from BigQuery), so every view and every day type can be split by
 * hour the same way.
 */
@Service
public class PeriodOdService {

    private final PeriodOdRepository repository;

    private final Cache<String, List<PeriodLeg>> legs = Caffeine.newBuilder()
            .maximumSize(300).expireAfterWrite(Duration.ofHours(1)).build();
    private final Cache<String, List<PeriodDay>> days = Caffeine.newBuilder()
            .maximumSize(300).expireAfterWrite(Duration.ofHours(1)).build();
    private final Cache<String, Map<String, double[]>> profiles = Caffeine.newBuilder()
            .maximumSize(3000).expireAfterWrite(Duration.ofDays(1)).build();
    private final Cache<String, List<PeriodOdRepository.ExcludedDay>> excluded = Caffeine.newBuilder()
            .maximumSize(300).expireAfterWrite(Duration.ofHours(1)).build();
    private final Cache<String, Object> single = Caffeine.newBuilder()
            .maximumSize(10).expireAfterWrite(Duration.ofDays(1)).build();

    public PeriodOdService(PeriodOdRepository repository) {
        this.repository = repository;
    }

    /** First and last month with route OD. */
    @SuppressWarnings("unchecked")
    public List<YearMonth> range() {
        List<LocalDate> r = (List<LocalDate>) single.get("range", k -> repository.monthRange());
        return r.isEmpty() ? List.of() : List.of(YearMonth.from(r.get(0)), YearMonth.from(r.get(1)));
    }

    /** Refuses a period outside the data, before any OD query. */
    public void requireInRange(Period period) {
        List<YearMonth> r = range();
        if (r.isEmpty()) {
            throw new IllegalArgumentException("no route OD in BigQuery yet");
        }
        if (period.from().isBefore(r.get(0)) || period.to().isAfter(r.get(1))) {
            throw new IllegalArgumentException("period must lie within " + r.get(0) + ".." + r.get(1) + ", got " + period);
        }
    }

    public List<PeriodDay> days(Period period, String dayType) {
        requireInRange(period);
        return days.get(period + "|" + dayType, k -> repository.days(period, dayType));
    }

    /** TOPIS bus days of the period and day type left out of its mean, with the reason. */
    public List<PeriodOdRepository.ExcludedDay> excludedBusDays(Period period, String dayType) {
        requireInRange(period);
        return excluded.get(period + "|" + dayType, k -> repository.excludedDays(period, dayType));
    }

    /** Bus days of the period (the ferry runs on a subset), for "weekday sample: 3 days". */
    public List<LocalDate> busDays(Period period, String dayType) {
        return days(period, dayType).stream().filter(d -> d.mode().equals("bus")).map(PeriodDay::date).toList();
    }

    public List<PeriodLeg> legsByRoutes(OdBasis basis, Collection<String> routes) {
        requireInRange(basis.period());
        String key = "routes|" + basis.period() + "|" + basis.dayType() + "|" + new TreeSet<>(routes);
        return legs.get(key, k -> repository.legsByRoutes(basis.period(), basis.dayType(), routes));
    }

    public List<PeriodLeg> legsByStops(OdBasis basis, Collection<String> keys, boolean alighting) {
        requireInRange(basis.period());
        String key = (alighting ? "alight|" : "board|") + basis.period() + "|" + basis.dayType() + "|" + new TreeSet<>(keys);
        return legs.get(key, k -> repository.legsByStops(basis.period(), basis.dayType(), keys, alighting));
    }

    /** Profiles of the routes for the day type; routes not yet cached are read in one query. */
    public Map<String, double[]> profiles(String dayType, Collection<String> routes) {
        Set<String> wanted = new TreeSet<>();
        routes.forEach(r -> wanted.add(r.strip()));
        Map<String, double[]> out = new HashMap<>();
        List<String> missing = new ArrayList<>();
        for (String route : wanted) {
            Map<String, double[]> cached = profiles.getIfPresent(dayType + "|" + route);
            if (cached == null) {
                missing.add(route);
            } else {
                out.putAll(cached);
            }
        }
        if (!missing.isEmpty()) {
            Map<String, double[]> read = repository.profiles(dayType, missing);
            Map<String, Map<String, double[]>> byRoute = new HashMap<>();
            missing.forEach(r -> byRoute.put(r, new HashMap<>()));
            read.forEach((k, v) -> byRoute.computeIfAbsent(k.substring(0, k.indexOf('|')), x -> new HashMap<>()).put(k, v));
            byRoute.forEach((r, m) -> profiles.put(dayType + "|" + r, m));
            out.putAll(read);
        }
        return out;
    }

    public double[] defaultProfile() {
        return (double[]) single.get("default", k -> repository.defaultProfile());
    }

    /**
     * The legs with riders cut to the boarding hours; legs whose boarding stop
     * cannot be split, or that keep no riders, are left out. Without hours the
     * legs are returned as they are.
     */
    public <L> List<L> splitByHours(List<L> in, String dayType, Hours hours,
                                    Function<L, String> route, Function<L, String> boardArs,
                                    Function<L, Double> passengers, Scale<L> scale) {
        if (hours == null) {
            return in;
        }
        Set<String> routes = new TreeSet<>();
        in.forEach(l -> routes.add(route.apply(l)));
        Map<String, double[]> prof = profiles(dayType, routes);
        double[] fallback = defaultProfile();
        List<L> out = new ArrayList<>(in.size());
        for (L l : in) {
            double s = HourlyShares.share(route.apply(l), boardArs.apply(l), hours, prof, fallback);
            if (s > 0 && passengers.apply(l) > 0) {
                out.add(scale.apply(l, passengers.apply(l) * s));
            }
        }
        return out;
    }

    /**
     * For a legend fixed over the day: the largest value maxLoad gives for any
     * single boarding hour of the day's legs (0..23), so stepping through the
     * hours keeps one colour scale. Profiles are read once and cached.
     */
    public <L> double legendMax(List<L> dayLegs, String dayType,
                                Function<L, String> route, Function<L, String> boardArs,
                                Function<L, Double> passengers, Scale<L> scale,
                                java.util.function.ToDoubleFunction<List<L>> maxLoad) {
        double max = 0;
        for (int h = 0; h < 24; h++) {
            List<L> hour = splitByHours(dayLegs, dayType, new Hours(h, h), route, boardArs, passengers, scale);
            if (!hour.isEmpty()) {
                max = Math.max(max, maxLoad.applyAsDouble(hour));
            }
        }
        return max;
    }

    /** Copies a leg with new riders. */
    @FunctionalInterface
    public interface Scale<L> {
        L apply(L leg, double passengers);
    }
}
