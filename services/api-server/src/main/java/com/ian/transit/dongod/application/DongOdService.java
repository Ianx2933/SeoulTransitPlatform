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
        DongArea level = DongArea.parse(levelName, DongArea.DONG);
        level.requireCode(code);
        DongArea destLevel = DongArea.parse(destLevelName, level == DongArea.CITY ? DongArea.GU : level);
        if (destLevel == DongArea.CITY) {
            throw new IllegalArgumentException("dest must be dong or gu");
        }
        if (direction != null && !direction.equals("from") && !direction.equals("to")) {
            throw new IllegalArgumentException("direction must be from or to, got " + direction);
        }
        boolean from = !"to".equals(direction);
        int n = Math.max(1, Math.min(MAX_LIMIT, limit));
        String dayType = dayType(date);
        DongOdQueryRepository.Totals totals = repository.totals(dayType, level, code);
        if (totals.departures() == 0 && totals.arrivals() == 0) {
            throw new NotFoundException("No district OD for " + code + " on " + dayType);
        }
        return new DongOdResponse(level.name().toLowerCase(), code, level.nameOf(code, totals.dongName()),
                destLevel.name().toLowerCase(), dayType, from ? "from" : "to",
                totals.departures(), totals.arrivals(), totals.subwayDepartures(), totals.busDepartures(),
                repository.flows(dayType, level, code, destLevel, from, n));
    }

    public List<DongOdSummaryRow> summary(String date, String levelName) {
        return repository.summary(dayType(date), DongArea.parse(levelName, DongArea.DONG));
    }

    public DongRoutesResponse routes(String date, String levelName, String code) {
        DongArea level = DongArea.parse(levelName, DongArea.DONG);
        level.requireCode(code);
        String label = TypicalDay.label(date);
        DongRouteFlows.Result r = DongRouteFlows.group(repository.routeRows(label, level, code), DESTINATIONS_PER_ROUTE);
        return new DongRoutesResponse(code, dayType(date), r.boardings(), r.unplaced(), r.routes());
    }
}
