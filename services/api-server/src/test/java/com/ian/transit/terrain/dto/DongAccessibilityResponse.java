package com.ian.transit.terrain.dto;

import com.ian.transit.terrain.model.DongAccessibilityRow;
import com.ian.transit.terrain.model.TerrainDataset;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** The percentage denominator is measuredStops, not totalStops. */
public record DongAccessibilityResponse(
        String admCd, String admNm, long totalStops, long measuredStops,
        long missingSlopeStops, long steepStops, Double steepRatioPct,
        Double avgSlopePct, Double maxSlopePct, Double avgElevationM,
        String datasetVersion, String boundaryBaseDate
) {
    public static DongAccessibilityResponse from(DongAccessibilityRow r, TerrainDataset d) {
        Double ratio = r.measuredStops() == 0 ? null :
                BigDecimal.valueOf(r.steepStops()).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(r.measuredStops()), 1, RoundingMode.HALF_UP)
                        .doubleValue();
        return new DongAccessibilityResponse(r.admCd(), r.admNm(), r.totalStops(),
                r.measuredStops(), r.missingSlopeStops(), r.steepStops(), ratio,
                r.avgSlopePct(), r.maxSlopePct(), r.avgElevM(), d.datasetId(),
                d.boundaryBaseDate());
    }
}
