package com.ian.transit.dongod.application;

import com.ian.transit.common.TypicalDay;
import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.dongod.dto.DongOdResponse;
import com.ian.transit.dongod.dto.DongOdSummaryRow;
import com.ian.transit.dongod.dto.DongRoutesResponse;
import com.ian.transit.dongod.infrastructure.DongOdQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/** District (행정동) OD for the demand map: flows, map summary, routes boarding there. */
@Service
@RequiredArgsConstructor
public class DongOdService {

    private static final Pattern CODE = Pattern.compile("\\d{8}");
    public static final int MAX_LIMIT = 50;
    public static final int DESTINATIONS_PER_ROUTE = 5;

    private final DongOdQueryRepository repository;

    /** "weekday" | "saturday" | "sunday_holiday" from the date parameter, as od_dong_typical stores it. */
    static String dayType(String date) {
        String label = TypicalDay.label(date);
        return label.startsWith("typical_") ? label.substring("typical_".length()) : label;
    }

    public DongOdResponse flows(String date, String code, String direction, int limit) {
        requireCode(code);
        boolean from = !"to".equals(direction);
        if (direction != null && !direction.equals("from") && !direction.equals("to")) {
            throw new IllegalArgumentException("direction must be from or to, got " + direction);
        }
        int n = Math.max(1, Math.min(MAX_LIMIT, limit));
        String dayType = dayType(date);
        DongOdQueryRepository.Totals totals = repository.totals(dayType, code);
        if (totals.departures() == 0 && totals.arrivals() == 0) {
            throw new NotFoundException("No district OD for " + code + " on " + dayType);
        }
        return new DongOdResponse(code, totals.name(), dayType, from ? "from" : "to",
                totals.departures(), totals.arrivals(), repository.flows(dayType, code, from, n));
    }

    public List<DongOdSummaryRow> summary(String date) {
        return repository.summary(dayType(date));
    }

    public DongRoutesResponse routes(String date, String code) {
        requireCode(code);
        String label = TypicalDay.label(date);
        DongRouteFlows.Result r = DongRouteFlows.group(repository.routeRows(label, code), DESTINATIONS_PER_ROUTE);
        return new DongRoutesResponse(code, dayType(date), r.boardings(), r.unplaced(), r.routes());
    }

    private static void requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("district code must be 8 digits, got " + code);
        }
    }
}
