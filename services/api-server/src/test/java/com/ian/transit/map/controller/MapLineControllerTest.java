package com.ian.transit.map.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ian.transit.map.repository.MapDemandRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The route list is sent with a public Cache-Control so the Firebase CDN can serve it.
 */
@WebMvcTest(MapLineController.class)
class MapLineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MapDemandRepository mapDemandRepository;

    @Test
    void linesAreReturnedWithPublicCacheHeaders() throws Exception {
        when(mapDemandRepository.findLinesByMode("bus")).thenReturn(List.of("100", "143"));

        mockMvc.perform(get("/api/map/lines").param("mode", "bus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1]").value("143"))
                .andExpect(header().string("Cache-Control", "max-age=3600, public, s-maxage=21600"));
    }
}
