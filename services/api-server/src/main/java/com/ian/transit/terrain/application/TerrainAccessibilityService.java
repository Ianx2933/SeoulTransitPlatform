package com.ian.transit.terrain.application;

import com.ian.transit.terrain.api.TerrainBadRequestException;
import com.ian.transit.terrain.dto.DongAccessibilityResponse;
import com.ian.transit.terrain.dto.StopTerrainResponse;
import com.ian.transit.terrain.dto.TerrainDatasetResponse;
import com.ian.transit.terrain.infrastructure.TerrainQueryRepository;
import com.ian.transit.terrain.model.TerrainDataset;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class TerrainAccessibilityService {
    public static final int MAX_LIMIT = 1000;
    private final TerrainQueryRepository repository;
    private final TerrainSummaryReader summaryReader;

    public TerrainAccessibilityService(TerrainQueryRepository repository, TerrainSummaryReader summaryReader) {
        this.repository = repository;
        this.summaryReader = summaryReader;
    }

    public List<StopTerrainResponse> getSteepStops(double minSlope, int limit) {
        return getSteepStops(minSlope, limit, 0);
    }

    public List<StopTerrainResponse> getSteepStops(double minSlope, int limit, int offset) {
        validateSlope(minSlope);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new TerrainBadRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0 || offset > 1_000_000) {
            throw new TerrainBadRequestException("offset must be between 0 and 1000000");
        }
        TerrainDataset dataset = repository.requireActiveDataset();
        return repository.findStopsAboveSlope(dataset.datasetId(), minSlope, limit, offset)
                .stream().map(row -> StopTerrainResponse.from(row, dataset)).toList();
    }

    public List<DongAccessibilityResponse> getDongSummary(double minSlope, int minStops) {
        validateSlope(minSlope);
        if (minStops < 0 || minStops > 1_000_000) {
            throw new TerrainBadRequestException("minStops must be between 0 and 1000000");
        }
        // Resolve the current pointer on every request. Cached data is keyed by
        // the immutable snapshot ID, so publication cannot return stale results.
        TerrainDataset dataset = repository.requireActiveDataset();
        return summaryReader.getDongSummary(dataset, minSlope, minStops);
    }

    public TerrainDatasetResponse getMetadata() {
        return TerrainDatasetResponse.from(repository.requireActiveDataset());
    }

    private static void validateSlope(double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new TerrainBadRequestException("minSlope must be a finite, non-negative percentage grade");
        }
    }
}
