package com.ian.transit.terrain.application;

import com.ian.transit.terrain.dto.DongAccessibilityResponse;
import com.ian.transit.terrain.infrastructure.TerrainQueryRepository;
import com.ian.transit.terrain.model.TerrainDataset;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

/**
 * Separate bean so calls pass through the Spring caching proxy.
 *
 * A @Cacheable method invoked from another method of the same class bypasses
 * the proxy entirely and the annotation has no effect, so this cannot be
 * folded back into TerrainAccessibilityService.
 *
 * The cache key includes datasetId. Snapshots are immutable, so publishing a
 * new dataset yields new keys and no explicit invalidation is needed.
 */
@Service
@RequiredArgsConstructor
public class TerrainSummaryReader {
    private final TerrainQueryRepository repository;

    @Cacheable(cacheNames = "terrainDongSummary",
            key = "new org.springframework.cache.interceptor.SimpleKey(#p0.datasetId(), #p1, #p2)")
    public List<DongAccessibilityResponse> getDongSummary(TerrainDataset dataset, double minSlope, int minStops) {
        return repository.summariseByDong(dataset.datasetId(), minSlope, minStops).stream()
                .map(row -> DongAccessibilityResponse.from(row, dataset)).toList();
    }
}
