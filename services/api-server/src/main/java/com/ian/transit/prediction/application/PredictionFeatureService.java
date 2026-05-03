package com.ian.transit.prediction.application;

import com.ian.transit.prediction.dto.PredictionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Builds model input features required by the Flask XGBoost service.
 *
 * Spring prepares operational features (dayOfWeek, isHoliday, prevWeek, prevMonth)
 * using the database. The Flask service only performs inference.
 */
@Service
@RequiredArgsConstructor
public class PredictionFeatureService {

    private final JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * dayOfWeek uses Monday=0, Sunday=6
     */
    public PredictionRequest buildPredictionRequest(String routeNo, String arsNo, LocalDate targetDate) {
        int dayOfWeek = targetDate.getDayOfWeek().getValue() - 1;
        int isHoliday = isHoliday(targetDate) ? 1 : 0;
        int month = targetDate.getMonthValue();

        double prevWeek = getPreviousPassengers(routeNo, arsNo, targetDate, 7);
        double prevMonth = getPreviousPassengers(routeNo, arsNo, targetDate, 30);

        return new PredictionRequest(routeNo, arsNo, dayOfWeek, isHoliday, month, prevWeek, prevMonth);
    }

    /**
     * Checks holiday_config table.
     */
    private boolean isHoliday(LocalDate date) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM holiday_config WHERE 날짜 = ?",
                Integer.class,
                date
        );
        return count != null && count > 0;
    }

    /**
     * Reads lag features from analysis_table_final.
     *
     * UPDATE: Uses corrected OD table instead of legacy daily_od_data.
     */
    private double getPreviousPassengers(String routeNo, String arsNo, LocalDate baseDate, int daysAgo) {
        LocalDate targetDate = baseDate.minusDays(daysAgo);

        String paddedArsNo = normalizeArs(arsNo);
        String strippedArsNo = stripLeadingZeros(paddedArsNo);

        String sql = """
                SELECT COALESCE(SUM(CAST(승객수 AS DOUBLE PRECISION)), 0)
                FROM analysis_table_final
                WHERE 노선명 = ?
                  AND (승차_정류장ars = ? OR 승차_정류장ars = ?)
                  AND 기준일자 = ?
                """;

        Double result = jdbcTemplate.queryForObject(
                sql,
                Double.class,
                routeNo,
                paddedArsNo,
                strippedArsNo,
                targetDate.format(DATE_FORMAT)  // "20251111" 형식
        );

        return result == null ? 0.0 : result;
    }

    /**
     * Normalize ARS to 5-digit format.
     */
    private String normalizeArs(String value) {
        String normalized = value.trim();

        if (!normalized.matches("\\d+")) {
            throw new IllegalArgumentException("arsNo must contain digits only");
        }

        if (normalized.length() > 5) {
            throw new IllegalArgumentException("arsNo must be at most 5 digits");
        }

        return normalized.length() == 5
                ? normalized
                : "0".repeat(5 - normalized.length()) + normalized;
    }

    /**
     * Remove leading zeros for compatibility with legacy data.
     */
    private String stripLeadingZeros(String value) {
        String stripped = value.replaceFirst("^0+(?!$)", "");
        return stripped.isBlank() ? "0" : stripped;
    }
}
