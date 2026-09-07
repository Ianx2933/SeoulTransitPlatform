package com.ian.transit.terrain.model;

import java.time.Instant;

/** An immutable published snapshot; activation is stored separately. */
public record TerrainDataset(
        String datasetId, String sourceId, String slopeMethod,
        String boundaryBaseDate, Instant processedAt, Instant publishedAt,
        Instant activatedAt, long stopCount, long measuredStopCount,
        long assignedStopCount, long ambiguousBoundaryStopCount
) {}
