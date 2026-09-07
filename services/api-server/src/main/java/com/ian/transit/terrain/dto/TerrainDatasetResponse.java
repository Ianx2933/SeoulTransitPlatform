package com.ian.transit.terrain.dto;

import com.ian.transit.terrain.model.TerrainDataset;
import java.time.Instant;

public record TerrainDatasetResponse(
        String datasetVersion, String sourceId, String slopeMethod,
        String boundaryBaseDate, Instant processedAt, Instant publishedAt,
        Instant activatedAt, long stopCount, long measuredStopCount,
        long assignedStopCount, long ambiguousBoundaryStopCount,
        String measurement
) {
    public static TerrainDatasetResponse from(TerrainDataset d) {
        return new TerrainDatasetResponse(d.datasetId(), d.sourceId(), d.slopeMethod(),
                d.boundaryBaseDate(), d.processedAt(), d.publishedAt(), d.activatedAt(),
                d.stopCount(), d.measuredStopCount(), d.assignedStopCount(),
                d.ambiguousBoundaryStopCount(),
                "DEM-cell terrain grade; not a measured pavement grade or accessibility certification");
    }
}
