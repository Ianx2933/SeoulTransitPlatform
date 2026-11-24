package com.ian.transit.period.api;

import com.ian.transit.period.OdBasis;
import com.ian.transit.period.Period;
import com.ian.transit.period.application.PeriodOdService;
import com.ian.transit.period.infrastructure.PeriodOdRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * What the period picker needs:
 *
 *   GET /api/period/range                                      first and last month with route OD
 *   GET /api/period/days?period=2026-03..2026-05&date=weekday  the days a period averages over
 *
 * Route OD has about one day per day type a month, so the page shows the sample
 * ("weekday sample: 3 days, 03-11 · 04-15 · 05-13") next to every period figure.
 */
@RestController
@RequestMapping("/api/period")
public class PeriodController {

    public record RangeResponse(YearMonth first, YearMonth last, int maxMonths) {
    }

    /** excluded: the period's TOPIS days of this type left out of the mean (Seollal, Chuseok, strikes). */
    public record DaysResponse(String dayType, String period, int nDays, List<LocalDate> days,
                               List<PeriodOdRepository.ExcludedDay> excluded) {
    }

    private final PeriodOdService service;

    public PeriodController(PeriodOdService service) {
        this.service = service;
    }

    @GetMapping("/range")
    public ResponseEntity<RangeResponse> range() {
        List<YearMonth> r = service.range();
        return ResponseEntity.ok(r.isEmpty() ? new RangeResponse(null, null, Period.MAX_MONTHS)
                : new RangeResponse(r.get(0), r.get(1), Period.MAX_MONTHS));
    }

    @GetMapping("/days")
    public ResponseEntity<DaysResponse> days(
        @RequestParam("period") String period,
        @RequestParam(value = "date", required = false) String date
    ) {
        OdBasis basis = OdBasis.of(date, period, null, null);
        List<LocalDate> days = service.busDays(basis.period(), basis.dayType());
        return ResponseEntity.ok(new DaysResponse(basis.dayType(), basis.period().toString(), days.size(), days,
                service.excludedBusDays(basis.period(), basis.dayType())));
    }
}
