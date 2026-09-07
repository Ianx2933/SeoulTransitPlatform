package com.ian.transit.terrain;

import com.ian.transit.terrain.application.TerrainSummaryReader;
import com.ian.transit.terrain.infrastructure.TerrainQueryRepository;
import com.ian.transit.terrain.model.DongAccessibilityRow;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerrainSummaryReaderTest {
    @Configuration @EnableCaching
    static class Config {
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager("terrainDongSummary"); }
        @Bean TerrainQueryRepository repository() { return mock(TerrainQueryRepository.class); }
        @Bean TerrainSummaryReader reader(TerrainQueryRepository r) { return new TerrainSummaryReader(r); }
    }
    @Test void cacheIsVersionedAndParametersArePartOfTheKey() {
        try(var context=new AnnotationConfigApplicationContext(Config.class)) {
            var repo=context.getBean(TerrainQueryRepository.class);
            var reader=context.getBean(TerrainSummaryReader.class);
            when(repo.summariseByDong(anyString(),anyDouble(),anyInt())).thenReturn(List.of(
                    new DongAccessibilityRow("A","Dong",1,1,0,1,8.0,8.0,10.0)));
            var v1=TerrainAccessibilityServiceTest.dataset("v1");
            var v2=TerrainAccessibilityServiceTest.dataset("v2");
            assertEquals(reader.getDongSummary(v1,8,10),reader.getDongSummary(v1,8,10));
            reader.getDongSummary(v2,8,10);
            reader.getDongSummary(v1,9,10);
            reader.getDongSummary(v1,8,11);
            verify(repo,times(1)).summariseByDong("v1",8,10);
            verify(repo,times(1)).summariseByDong("v2",8,10);
            verify(repo,times(1)).summariseByDong("v1",9,10);
            verify(repo,times(1)).summariseByDong("v1",8,11);
        }
    }
}
