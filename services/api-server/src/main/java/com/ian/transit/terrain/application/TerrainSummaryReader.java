package com.ian.transit.terrain.application;

import com.ian.transit.terrain.dto.DongAccessibilityResponse;
import com.ian.transit.terrain.infrastructure.TerrainQueryRepository;
import com.ian.transit.terrain.model.TerrainDataset;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/** Separate bean ensures calls pass through the Spring caching proxy. */
@Service
public class TerrainSummaryReader {
    private final TerrainQueryRepository repository;
    public TerrainSummaryReader(TerrainQueryRepository repository) { this.repository = repository; }

    @Cacheable(cacheNames = "terrainDongSummary",
            key = "new org.springframework.cache.interceptor.SimpleKey(#p0.datasetId(), #p1, #p2)")
    public List<DongAccessibilityResponse> getDongSummary(TerrainDataset dataset, double minSlope, int minStops) {
        return repository.summariseByDong(dataset.datasetId(), minSlope, minStops).stream()
                .map(row -> DongAccessibilityResponse.from(row, dataset)).toList();
    }
}
