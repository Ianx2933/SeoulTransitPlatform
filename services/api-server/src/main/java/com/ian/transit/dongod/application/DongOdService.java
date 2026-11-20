package com.ian.transit.dongod.application;

import com.ian.transit.common.TypicalDay;
import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.dongod.dto.DongOdResponse;
import com.ian.transit.dongod.dto.DongOdSummaryRow;
import com.ian.transit.dongod.dto.DongReachResponse;
import com.ian.transit.dongod.dto.DongRoutesResponse;
import com.ian.transit.dongod.infrastructure.DongOdQueryRepository;
import com.ian.transit.dongod.infrastructure.RouteLoadQueryRepository;
import com.ian.transit.period.OdBasis;
import com.ian.transit.period.application.PeriodOdService;
import com.ian.transit.period.infrastructure.PeriodDongRepository;
import com.ian.transit.period.infrastructure.PeriodDongRepository.End;
import com.ian.transit.period.infrastructure.PeriodDongRepository.Pair;
import com.ian.transit.period.infrastructure.PeriodOdRepository.PeriodLeg;
import com.ian.transit.roadload.application.RoadLoadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.TreeSet;

/**
 * District OD for the demand map at three levels (행정동, 구, Seoul): flows by
 * rail and bus, the map summary, and the bus routes boarding in an area.
 */
@Service
@RequiredArgsConstructor
public class DongOdService {

    public static final int MAX_LIMIT = 50;
    public static final int DESTINATIONS_PER_ROUTE = 5;

    private final DongOdQueryRepository repository;
    private final RouteLoadQueryRepository legRepository;
    private final PeriodOdService periodService;
    private final PeriodDongRepository periodDong;

    /** "weekday" | "saturday" | "sunday_holiday" from the date parameter, as od_dong_typical stores it. */
    static String dayType(String date) {
        String label = TypicalDay.label(date);
        return label.startsWith("typical_") ? label.substring("typical_".length()) : label;
    }

    /**
     * @param destLevelName unit of the rows; defaults to the area's own level, and
     *                      to gu when the area is all of Seoul
     */
    public DongOdResponse flows(String date, String levelName, String code, String destLevelName,
                                String direction, int limit) {
        return flows(OdBasis.of(date, null, null, null), levelName, code, destLevelName, direction, limit);
    }

    /**
     * A period reads averaged pairs of the chosen months from BigQuery and groups
     * them as the SQL does (DistrictOd). Hours do not apply: TOPIS district OD is
     * a day's total.
     */
    public DongOdResponse flows(OdBasis basis, String levelName, String code, String destLevelName,
                                String direction, int limit) {
        DongArea level = DongArea.parse(levelName, DongArea.DONG);
        level.requireCode(code);
        DongArea destLevel = DongArea.parse(destLevelName,
                level == DongArea.CITY ? DongArea.GU : level == DongArea.GROUP ? DongArea.DONG : level);
        if (destLevel == DongArea.CITY || destLevel == DongArea.GROUP) {
            throw new IllegalArgumentException("dest must be dong or gu");
        }
        if (direction != null && !direction.equals("from") && !direction.equals("to")) {
            throw new IllegalArgumentException("direction must be from or to, got " + direction);
        }
        boolean from = !"to".equals(direction);
        int n = Math.max(1, Math.min(MAX_LIMIT, limit));
        String dayType = basis.dayType();
        List<Pair> pairs = basis.typical() ? null : periodPairs(basis, level, code, End.EITHER);
        DongOdQueryRepository.Totals totals = pairs == null ? repository.totals(dayType, level, code)
                : DistrictOd.totals(pairs, level, code);
        if (totals.departures() == 0 && totals.arrivals() == 0) {
            throw new NotFoundException("No district OD for " + code + " on " + basis);
        }
        List<DongOdResponse.Flow> flows = pairs == null ? repository.flows(dayType, level, code, destLevel, from, n)
                : DistrictOd.flows(pairs, level, code, destLevel, from, n);
        return new DongOdResponse(level.name().toLowerCase(), code, level.nameOf(code, totals.dongName()),
                destLevel.name().toLowerCase(), dayType, from ? "from" : "to",
                totals.departures(), totals.arrivals(), totals.subwayDepartures(), totals.busDepartures(), flows);
    }

    public List<DongOdSummaryRow> summary(String date, String levelName) {
        return summary(OdBasis.of(date, null, null, null), levelName);
    }

    public List<DongOdSummaryRow> summary(OdBasis basis, String levelName) {
        DongArea level = DongArea.parse(levelName, DongArea.DONG);
        if (level == DongArea.GROUP) {
            throw new IllegalArgumentException("summary is by dong, gu or city");
        }
        if (basis.typical()) {
            return repository.summary(basis.dayType(), level);
        }
        return DistrictOd.summary(periodPairs(basis, DongArea.CITY, DongArea.SEOUL, End.ANY), level);
    }

    private List<Pair> periodPairs(OdBasis basis, DongArea level, String code, End end) {
        periodService.requireInRange(basis.period());
        return periodDong.pairs(basis.period(), basis.dayType(), level, code, end);
    }

    /**
     * How far the area's riders travel (direction from) or its arrivals come
     * from (to), with bus riders outside Seoul from route stop OD.
     */
    public DongReachResponse reach(String date, String levelName, String code, String direction) {
        return reach(OdBasis.of(date, null, null, null), levelName, code, direction);
    }

    /**
     * How far the area's riders travel (direction from) or its arrivals come
     * from (to), with Seoul city bus riders outside Seoul from route stop OD.
     * A period reads both from BigQuery; distances use the same district points.
     */
    public DongReachResponse reach(OdBasis basis, String levelName, String code, String direction) {
        DongArea level = DongArea.parse(levelName, DongArea.DONG);
        level.requireCode(code);
        if (direction != null && !direction.equals("from") && !direction.equals("to")) {
            throw new IllegalArgumentException("direction must be from or to, got " + direction);
        }
        boolean from = !"to".equals(direction);
        String dayType = basis.dayType();
        List<DongReach.Row> rows;
        if (basis.typical()) {
            rows = repository.reachRows(dayType, level, code, from);
        } else {
            List<Pair> pairs = periodPairs(basis, level, code, from ? End.ORIGIN : End.DEST);
            Set<String> codes = new HashSet<>();
            pairs.forEach(p -> {
                codes.add(p.originCd());
                codes.add(p.destCd());
            });
            codes.remove(DongArea.OUTSIDE);
            rows = DistrictOd.reachRows(pairs, level, code, from, repository.dongPoints(codes));
        }
        DongReach.Result r = DongReach.summarize(rows);
        if (r.total() == 0) {
            throw new NotFoundException("No district OD for " + code + " on " + basis);
        }
        // All of Seoul has no area stops to start from (every stop would be one).
        BusOutside.Result bus = level == DongArea.CITY ? null
                : BusOutside.summarize(basis.typical()
                        ? repository.busOutsideRows(basis.label(), level, code, from)
                        : periodBusOutside(basis, level, code, from));
        return DongReachResponse.of(level.name().toLowerCase(), code, dayType, from ? "from" : "to", r, bus);
    }

    /**
     * BUS_OUTSIDE_SQL for a period: legs of the area's stops from BigQuery, the
     * other stop outside the area placed in its district (stopDongs), Seoul
     * districts left out, km between this end's district point (or the area's)
     * and the other end's.
     */
    private List<BusOutside.Row> periodBusOutside(OdBasis basis, DongArea level, String code, boolean from) {
        List<String> keys = legRepository.areaStopKeys(level, code);
        Set<String> keySet = new HashSet<>(keys);
        Map<String, Double> byPair = new LinkedHashMap<>();
        for (PeriodLeg l : periodService.legsByStops(basis, keys, !from)) {
            String here = from ? l.boardArs() : l.alightArs();
            String there = from ? l.alightArs() : l.boardArs();
            if (keySet.contains(there)) {
                continue;
            }
            byPair.merge(lpad5(here) + "|" + lpad5(there), l.passengers(), Double::sum);
        }
        Set<String> stops = new HashSet<>();
        byPair.keySet().forEach(k -> stops.addAll(List.of(k.split("\\|", -1))));
        Map<String, DongOdQueryRepository.StopDong> dongs = repository.stopDongs(stops);
        Set<String> codes = dongs.values().stream().map(DongOdQueryRepository.StopDong::code).collect(Collectors.toSet());
        Map<String, double[]> points = repository.dongPoints(codes);
        double[] areaPoint = repository.areaPoint(level, code);
        Map<String, BusOutside.Row> out = new LinkedHashMap<>();
        byPair.forEach((k, p) -> {
            String[] ends = k.split("\\|", -1);
            DongOdQueryRepository.StopDong h = dongs.get(ends[0]);
            DongOdQueryRepository.StopDong t = dongs.get(ends[1]);
            if (t != null && t.code().startsWith(DongArea.SEOUL)) {
                return;
            }
            double[] hp = h != null && DistrictOd.inArea(level, code, h.code()) ? points.get(h.code()) : areaPoint;
            double[] tp = t == null ? null : points.get(t.code());
            Double km = hp == null || tp == null ? null : DistrictOd.km(hp, tp);
            String key = (t == null ? "" : t.code()) + "|" + km;
            out.merge(key, new BusOutside.Row(t == null ? null : t.code(), t == null ? null : t.name(), km, p),
                    (a, b) -> new BusOutside.Row(a.admCd(), a.admNm(), a.km(), a.passengers() + b.passengers()));
        });
        return List.copyOf(out.values());
    }

    /** LPAD(x, 5, '0') as BUS_OUTSIDE_SQL keys the legs. */
    static String lpad5(String x) {
        if (x == null) {
            return "";
        }
        return x.length() >= 5 ? x.substring(0, 5) : "0".repeat(5 - x.length()) + x;
    }

    public DongRoutesResponse routes(String date, String levelName, String code) {
        return routes(OdBasis.of(date, null, null, null), levelName, code);
    }

    /**
     * Bus routes boarding in the area with the districts their riders alight
     * in. The plain typical day is one Cloud SQL query as before; a period
     * takes the legs from BigQuery, boarding hours cut them, and the alighting
     * stops are placed in districts the same way (stopDongs).
     */
    public DongRoutesResponse routes(OdBasis basis, String levelName, String code) {
        DongArea level = DongArea.parse(levelName, DongArea.DONG);
        level.requireCode(code);
        if (basis.plain()) {
            DongRouteFlows.Result r = DongRouteFlows.group(repository.routeRows(basis.label(), level, code),
                    DESTINATIONS_PER_ROUTE);
            return new DongRoutesResponse(code, basis.dayType(), r.boardings(), r.unplaced(), r.routes());
        }
        List<RouteLoadCalculator.Leg> legs = basis.typical()
                ? legRepository.legs(basis.label(), level, code, "od")
                : periodService.legsByStops(basis, legRepository.areaStopKeys(level, code), false).stream()
                        .map(l -> new RouteLoadCalculator.Leg(l.route(), l.boardArs(), l.alightArs(), l.passengers(),
                                l.boardSeq(), l.alightSeq()))
                        .toList();
        legs = RoadLoadService.split(periodService, basis, legs);
        Set<String> alight = new TreeSet<>();
        legs.forEach(l -> {
            String a = RouteLoadCalculator.pad(l.alightArs());
            if (a != null) {
                alight.add(a);
            }
        });
        Map<String, DongOdQueryRepository.StopDong> dongs = repository.stopDongs(alight);
        Map<String, double[]> sums = new LinkedHashMap<>();
        Map<String, DongRouteFlows.Row> keyRow = new LinkedHashMap<>();
        for (RouteLoadCalculator.Leg l : legs) {
            String a = RouteLoadCalculator.pad(l.alightArs());
            DongOdQueryRepository.StopDong d = a == null ? null : dongs.get(a);
            String destCode = d == null ? null : d.code();
            String key = l.route() + "|" + destCode;
            sums.computeIfAbsent(key, k -> new double[1])[0] += l.passengers();
            keyRow.putIfAbsent(key, new DongRouteFlows.Row(l.route(), destCode,
                    d == null ? null : DongArea.placeName(d.code(), d.name()), 0));
        }
        List<DongRouteFlows.Row> rows = keyRow.entrySet().stream()
                .map(e -> new DongRouteFlows.Row(e.getValue().route(), e.getValue().destCode(),
                        e.getValue().destName(), sums.get(e.getKey())[0]))
                .toList();
        DongRouteFlows.Result r = DongRouteFlows.group(rows, DESTINATIONS_PER_ROUTE);
        return new DongRoutesResponse(code, basis.dayType(), r.boardings(), r.unplaced(), r.routes());
    }
}
