package com.ian.transit.map.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ian.transit.common.exception.GlobalExceptionHandler;
import com.ian.transit.map.exception.NodeNotFoundException;
import com.ian.transit.map.service.MapDemandService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MVC slice tests for the externally visible HTTP error contract.
 */
@WebMvcTest(MapDemandController.class)
@Import(GlobalExceptionHandler.class)
class MapDemandControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MapDemandService mapDemandService;

    @Test
    void invalidRequestFromServiceIsReturnedAsStructured400() throws Exception {
        when(mapDemandService.getMapDemand(
                eq("tram"), any(), any(), anyString(), anyInt(), any(), any(), any()
        )).thenThrow(new IllegalArgumentException("mode must be either 'bus' or 'subway'"));

        mockMvc.perform(get("/api/map/demand")
                        .param("mode", "tram")
                        .param("dayType", "mon")
                        .param("hour", "8"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("mode must be either 'bus' or 'subway'"))
                .andExpect(jsonPath("$.path").value("/api/map/demand"));
    }

    @Test
    void missingNodeIsReturnedAsStructured404() throws Exception {
        when(mapDemandService.getNodeDemandDetail(
                eq("bus"), eq("99999"), eq("mon"), any(), eq("average"), eq(8), any()
        )).thenThrow(new NodeNotFoundException("No demand found for node 99999"));

        mockMvc.perform(get("/api/map/node-detail")
                        .param("mode", "bus")
                        .param("nodeId", "99999")
                        .param("dayType", "mon")
                        .param("hour", "8"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("No demand found for node 99999"))
                .andExpect(jsonPath("$.path").value("/api/map/node-detail"));
    }
    @Test
    void missingRequiredModeIsReturnedAs400InsteadOfCatchAll500() throws Exception {
        mockMvc.perform(get("/api/map/demand")
                        .param("dayType", "mon")
                        .param("hour", "8"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/map/demand"));
    }

    @Test
    void nonNumericHourIsReturnedAs400InsteadOfCatchAll500() throws Exception {
        mockMvc.perform(get("/api/map/demand")
                        .param("mode", "bus")
                        .param("dayType", "mon")
                        .param("hour", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/map/demand"));
    }

}
