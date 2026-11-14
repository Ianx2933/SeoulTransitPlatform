package com.ian.transit.curatedod.application;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.curatedod.dto.RouteSegmentResponse;
import com.ian.transit.curatedod.dto.RouteStopDetailResponse;
import com.ian.transit.curatedod.dto.RouteStopsResponse;
import com.ian.transit.curatedod.infrastructure.CuratedOdQueryRepository;
import com.ian.transit.curatedod.infrastructure.TypicalOdRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Route analysis endpoints: loads one route's typical-day OD and hands it to
 * {@link RouteOdAnalyzer}. Rows without a boarding or alighting sequence cannot
 * be placed on the route and are left out.
 */
@Service
@RequiredArgsConstructor
public class RouteAnalysisService {

    private final CuratedOdQueryRepository queryRepository;

    public RouteStopsResponse stops(String 기준일자, String 노선명) {
        return analyzer(기준일자, 노선명).stops();
    }

    public RouteSegmentResponse segment(String 기준일자, String 노선명, int from, int to) {
        return analyzer(기준일자, 노선명).segment(from, to);
    }

    public RouteStopDetailResponse stop(String 기준일자, String 노선명, int seq) {
        return analyzer(기준일자, 노선명).stop(seq);
    }

    private RouteOdAnalyzer analyzer(String 기준일자, String 노선명) {
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
        return new RouteOdAnalyzer(기준일자, 노선명, nDays, legs);
    }
}
