package com.ian.transit.map.controller;

import com.ian.transit.map.repository.MapDemandRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for line metadata APIs.
 */
@RestController
@RequestMapping("/api/map")
public class MapLineController {

    private final MapDemandRepository mapDemandRepository;

    public MapLineController(MapDemandRepository mapDemandRepository) {
        this.mapDemandRepository = mapDemandRepository;
    }

    /**
     * Returns available line names for a transport mode.
     */
    @GetMapping("/lines")
    public List<String> getLines(
            @RequestParam String mode
    ) {
        return mapDemandRepository.findLinesByMode(mode);
    }
}
