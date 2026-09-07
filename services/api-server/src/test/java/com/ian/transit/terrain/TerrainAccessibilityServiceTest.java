package com.ian.transit.terrain;

import com.ian.transit.terrain.api.TerrainBadRequestException;
import com.ian.transit.terrain.application.TerrainAccessibilityService;
import com.ian.transit.terrain.application.TerrainSummaryReader;
import com.ian.transit.terrain.dto.DongAccessibilityResponse;
import com.ian.transit.terrain.infrastructure.TerrainQueryRepository;
import com.ian.transit.terrain.model.DongAccessibilityRow;
import com.ian.transit.terrain.model.StopTerrainRow;
import com.ian.transit.terrain.model.TerrainDataset;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerrainAccessibilityServiceTest {
    private TerrainQueryRepository repository;
    private TerrainSummaryReader reader;
    private TerrainAccessibilityService service;
    static TerrainDataset dataset(String id) {
        return new TerrainDataset(id,"GLO-30","documented-method","20260101",
                Instant.EPOCH,Instant.EPOCH,Instant.EPOCH,4,3,4,1);
    }
    @BeforeEach void setup() {
        repository=mock(TerrainQueryRepository.class);
        reader=mock(TerrainSummaryReader.class);
        service=new TerrainAccessibilityService(repository,reader);
        when(repository.requireActiveDataset()).thenReturn(dataset("v1"));
    }
    @Test void validatesBeforeQuerying() {
        for(double value:new double[]{-1,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}) {
            assertThrows(TerrainBadRequestException.class,()->service.getSteepStops(value,10));
            assertThrows(TerrainBadRequestException.class,()->service.getDongSummary(value,10));
        }
        for(int limit:new int[]{0,-1,1001})
            assertThrows(TerrainBadRequestException.class,()->service.getSteepStops(8,limit));
        assertThrows(TerrainBadRequestException.class,()->service.getSteepStops(8,10,-1));
        assertThrows(TerrainBadRequestException.class,()->service.getSteepStops(8,10,1_000_001));
        assertThrows(TerrainBadRequestException.class,()->service.getDongSummary(8,-1));
        verifyNoInteractions(reader);
        verify(repository,never()).findStopsAboveSlope(anyString(),anyDouble(),anyInt(),anyInt());
        verify(repository,never()).summariseByDong(anyString(),anyDouble(),anyInt());
    }
    @Test void allowsGradesAboveOneHundredAndPreservesPagination() {
        when(repository.findStopsAboveSlope("v1",125,1,7)).thenReturn(List.of(
                new StopTerrainRow("00001","01","Hill",127,37,null,125,null,null,"unmatched",0)));
        var result=service.getSteepStops(125,1,7);
        assertEquals(1,result.size());
        assertEquals("00001",result.get(0).nodeId());
        assertEquals(125,result.get(0).slopePct());
        assertNull(result.get(0).elevationM());
        assertNull(result.get(0).dongName());
        assertEquals("v1",result.get(0).datasetVersion());
        verify(repository).findStopsAboveSlope("v1",125,1,7);
    }
    @Test void ratioUsesMeasuredDenominatorAndNullIsNotZero() {
        var d=dataset("v1");
        var row=new DongAccessibilityRow("A","Dong",5,3,2,2,8.0,15.0,null);
        var dto=DongAccessibilityResponse.from(row,d);
        assertEquals(66.7,dto.steepRatioPct());
        assertEquals(5,dto.totalStops());
        assertEquals(3,dto.measuredStops());
        assertEquals(2,dto.missingSlopeStops());
        assertNull(dto.avgElevationM());
        var empty=DongAccessibilityResponse.from(
                new DongAccessibilityRow("B","Unknown",2,0,2,0,null,null,null),d);
        assertNull(empty.steepRatioPct());
        assertNull(empty.avgSlopePct());
        assertNull(empty.maxSlopePct());
    }
    @Test void selectsCurrentVersionBeforeDelegatingToCache() {
        service.getDongSummary(8,10);
        verify(reader).getDongSummary(dataset("v1"),8,10);
        when(repository.requireActiveDataset()).thenReturn(dataset("v2"));
        service.getDongSummary(8,10);
        verify(reader).getDongSummary(dataset("v2"),8,10);
        assertEquals("v2",service.getMetadata().datasetVersion());
    }
}
