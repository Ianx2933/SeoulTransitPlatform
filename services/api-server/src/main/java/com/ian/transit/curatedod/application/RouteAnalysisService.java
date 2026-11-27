package com.ian.transit.curatedod.application;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.curatedod.dto.RouteDongOdResponse;
import com.ian.transit.curatedod.dto.RouteSegmentResponse;
import com.ian.transit.curatedod.dto.RouteStopDetailResponse;
import com.ian.transit.curatedod.dto.RouteStopsResponse;
import com.ian.transit.curatedod.infrastructure.CuratedOdQueryRepository;
import com.ian.transit.curatedod.infrastructure.StopDistrictQueryRepository;
import com.ian.transit.curatedod.infrastructure.StopDistrictQueryRepository.StopDistrict;
import com.ian.transit.curatedod.infrastructure.TypicalOdRecord;
import com.ian.transit.period.OdBasis;
import com.ian.transit.period.application.PeriodOdService;
import com.ian.transit.period.infrastructure.PeriodOdRepository.PeriodLeg;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Route analysis endpoints: loads one route's typical-day OD and hands it to
 * {@link RouteOdAnalyzer}. Rows without a boarding or alighting sequence cannot
 * be placed on the route and are left out.
 *
 * A period (BigQuery) or boarding hours go through PeriodOdService: legs of
 * the period or the typical day, cut to the boarding hours by the boarding
 * stop's profile (hourly_profile, the rules of estimate_hourly_od.py). Until
 * 2026-11 one weekday hour was read from estimated_hourly_od on Cloud SQL;
 * that query ran past the statement timeout (no index on route and hour), and
 * the split gives the same rule for every day type and hour range.
 */
@Service
@RequiredArgsConstructor
public class RouteAnalysisService {

    private final CuratedOdQueryRepository queryRepository;
    private final StopDistrictQueryRepository districtRepository;
    private final PeriodOdService periodService;

    public RouteStopsResponse stops(OdBasis basis, String 노선명) {
        return analyzer(basis, 노선명).stops();
    }

    public RouteSegmentResponse segment(OdBasis basis, String 노선명, int from, int to) {
        return analyzer(basis, 노선명).segment(from, to);
    }

    public RouteStopDetailResponse stop(OdBasis basis, String 노선명, int seq, List<Double> reach) {
        return analyzer(basis, 노선명).stop(seq, reach);
    }

    public com.ian.transit.curatedod.dto.RouteStopsCombinedResponse stopsCombined(OdBasis basis, String 노선명,
                                                                                 List<Integer> seqs) {
        return analyzer(basis, 노선명).stops(seqs);
    }

    /**
     * The route's OD by administrative district: stop pairs summed into
     * boarding-to-alighting district pairs, with each district's boundary.
     */
    public RouteDongOdResponse dongOd(OdBasis basis, String 노선명) {
        String 기준일자 = basis.label();
        Integer hour = basis.hours() != null && basis.hours().single() ? basis.hours().from() : null;
        Loaded loaded = load(basis, 노선명);
        Set<String> ars = new LinkedHashSet<>();
        for (RouteOdAnalyzer.Leg leg : loaded.legs()) {
            ars.add(RouteDongOd.padArs(leg.boardArs()));
            ars.add(RouteDongOd.padArs(leg.alightArs()));
        }
        ars.remove(null);
        Map<String, StopDistrict> stopDistricts = districtRepository.districtsOf(ars);
        Map<String, String> codeOfArs = new LinkedHashMap<>();
        Map<String, String> nameOfCode = new LinkedHashMap<>();
        stopDistricts.forEach((stop, d) -> {
            codeOfArs.put(stop, d.code());
            nameOfCode.putIfAbsent(d.code(), d.name());
        });

        RouteDongOd.Result result = RouteDongOd.aggregate(loaded.legs(), codeOfArs);
        Map<String, String> geometry = districtRepository.boundaries(result.byDistrict().keySet());
        List<RouteDongOdResponse.District> districts = new ArrayList<>();
        result.byDistrict().forEach((code, sums) -> districts.add(new RouteDongOdResponse.District(
                code, nameOfCode.get(code), sums[0], sums[1], geometry.get(code))));
        return new RouteDongOdResponse(기준일자, 노선명, loaded.nDays(), hour,
                result.total(), result.unplaced(), districts, result.flows());
    }

    private RouteOdAnalyzer analyzer(OdBasis basis, String 노선명) {
        Loaded loaded = load(basis, 노선명);
        return new RouteOdAnalyzer(basis.label(), 노선명, loaded.nDays(), loaded.legs());
    }

    private record Loaded(List<RouteOdAnalyzer.Leg> legs, Integer nDays) {
    }

    private Loaded load(OdBasis basis, String 노선명) {
        if (basis.typical() && basis.hours() == null) {
            return typicalLegs(queryRepository.findAllBy기준일자And노선명(basis.label(), 노선명),
                    노선명, basis.label());
        }
        return loadSplit(basis, 노선명);
    }

    private static Loaded typicalLegs(List<TypicalOdRecord> records, String 노선명, String 기준일자) {
        List<RouteOdAnalyzer.Leg> legs = new ArrayList<>(records.size());
        Integer nDays = null;
        for (TypicalOdRecord r : records) {
            if (nDays == null) {
                nDays = r.getNDays();
            }
            if (r.get승차정류장순번() == null || r.get하차정류장순번() == null || r.get승객수() == null) {
                continue;
            }
            legs.add(new RouteOdAnalyzer.Leg(r.get승차정류장순번(), r.get승차정류장ARS(), r.get승차정류장명(),
                    r.get하차정류장순번(), r.get하차정류장ARS(), r.get하차정류장명(), r.get승객수()));
        }
        if (legs.isEmpty()) {
            throw new NotFoundException("No OD for route " + 노선명 + " on " + 기준일자);
        }
        return new Loaded(legs, nDays);
    }

    /** A period and/or boarding hours: legs from Cloud SQL or BigQuery, then cut to the hours. */
    private Loaded loadSplit(OdBasis basis, String 노선명) {
        List<RouteOdAnalyzer.Leg> legs;
        Integer nDays;
        if (basis.typical()) {
            Loaded typical = typicalLegs(queryRepository.findAllBy기준일자And노선명(basis.label(), 노선명),
                    노선명, basis.label());
            legs = typical.legs();
            nDays = typical.nDays();
        } else {
            legs = new ArrayList<>();
            for (PeriodLeg l : periodService.legsByRoutes(basis, List.of(노선명))) {
                if (l.boardSeq() != null && l.alightSeq() != null) {
                    legs.add(new RouteOdAnalyzer.Leg(l.boardSeq(), l.boardArs(), l.boardName(),
                            l.alightSeq(), l.alightArs(), l.alightName(), l.passengers()));
                }
            }
            nDays = periodService.busDays(basis.period(), basis.dayType()).size();
            if (legs.isEmpty()) {
                throw new NotFoundException("No OD for route " + 노선명 + " in " + basis);
            }
        }
        legs = periodService.splitByHours(legs, basis.dayType(), basis.hours(),
                l -> 노선명, RouteOdAnalyzer.Leg::boardArs, RouteOdAnalyzer.Leg::passengers,
                (l, p) -> new RouteOdAnalyzer.Leg(l.boardSeq(), l.boardArs(), l.boardName(),
                        l.alightSeq(), l.alightArs(), l.alightName(), p));
        if (legs.isEmpty()) {
            throw new NotFoundException("No riders on route " + 노선명 + " in " + basis);
        }
        return new Loaded(legs, nDays);
    }
}
