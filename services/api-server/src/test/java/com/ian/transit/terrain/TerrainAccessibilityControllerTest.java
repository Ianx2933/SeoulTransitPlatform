package com.ian.transit.terrain;

import com.ian.transit.terrain.api.TerrainAccessibilityController;
import com.ian.transit.terrain.api.TerrainExceptionHandler;
import com.ian.transit.terrain.api.TerrainUnavailableException;
import com.ian.transit.terrain.application.TerrainAccessibilityService;
import com.ian.transit.terrain.application.TerrainSummaryReader;
import com.ian.transit.terrain.infrastructure.TerrainQueryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.when;

@WebMvcTest(TerrainAccessibilityController.class)
@Import({TerrainAccessibilityService.class,TerrainExceptionHandler.class})
class TerrainAccessibilityControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean TerrainQueryRepository repository;
    @MockitoBean TerrainSummaryReader summaryReader;

    @Test void invalidInputsReturn400() throws Exception {
        mvc.perform(get("/api/stops/accessibility").param("minSlope","NaN"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TERRAIN_REQUEST"));
        mvc.perform(get("/api/stops/accessibility").param("limit","0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stops/accessibility").param("offset","-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stops/accessibility/summary").param("minStops","-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stops/accessibility").param("limit","not-a-number"))
                .andExpect(status().isBadRequest());
    }
    @Test void missingActiveDatasetReturns503() throws Exception {
        when(repository.requireActiveDataset()).thenThrow(new TerrainUnavailableException("No published terrain dataset is active"));
        mvc.perform(get("/api/stops/accessibility/metadata"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TERRAIN_DATA_UNAVAILABLE"));
    }
}
