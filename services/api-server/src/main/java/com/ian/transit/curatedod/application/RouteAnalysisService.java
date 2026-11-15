package com.ian.transit.curatedod.application;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.curatedod.dto.RouteSegmentResponse;
import com.ian.transit.curatedod.dto.RouteStopDetailResponse;
import com.ian.transit.curatedod.dto.RouteStopsResponse;
import com.ian.transit.curatedod.infrastructure.CuratedOdQueryRepository;
import com.ian.transit.curatedod.infrastructure.HourlyOdQueryRepository;
import com.ian.transit.curatedod.infrastructure.TypicalOdRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Route analysis endpoints: loads one route's typical-day OD and hands it to
 * {@link RouteOdAnalyzer}. Rows without a boarding or alighting sequence cannot
 * be placed on the route and are left out.
 *
 * With an hour, the same OD pairs are taken from estimated_hourly_od for the
 * riders who boarded in that hour, keeping the sequences of the typical day.
 */
@Service
@RequiredArgsConstructor
public class RouteAnalysisService {

    private final CuratedOdQueryRepository queryRepository;
    private final HourlyOdQueryRepository hourlyRepository;

    public RouteStopsResponse stops(String 기준일자, String 노선명, Integer hour) {
        return analyzer(기준일자, 노선명, hour).stops();
    }

    public RouteSegmentResponse segment(String 기준일자, String 노선명, Integer hour, int from, int to) {
        return analyzer(기준일자, 노선명, hour).segment(from, to);
    }

    public RouteStopDetailResponse stop(String 기준일자, String 노선명, Integer hour, int seq, List<Double> reach) {
        return analyzer(기준일자, 노선명, hour).stop(seq, reach);
    }

    private RouteOdAnalyzer analyzer(String 기준일자, String 노선명, Integer hour) {
        if (hour != null && (hour < 0 || hour > 23)) {
            throw new IllegalArgumentException("hour must be 0 to 23, got " + hour);
        }
        List<TypicalOdRecord> records = queryRepository.findAllBy기준일자And노선명(기준일자, 노선명);
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
        if (hour != null) {
            Map<String, Double> byPair = hourlyRepository.passengersByPair(기준일자, 노선명, hour);
            legs = RouteOdAnalyzer.withPassengers(legs, byPair);
            if (legs.isEmpty()) {
                throw new NotFoundException("No hourly OD for route " + 노선명 + " on " + 기준일자
                        + " at hour " + hour + " (hourly OD covers weekdays only)");
            }
        }
        return new RouteOdAnalyzer(기준일자, 노선명, nDays, legs);
    }
}
