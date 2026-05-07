package com.ian.transit.map.service;

import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.repository.MapDemandRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Application service for map demand use cases.
 */
@Service
public class MapDemandService {

    private final MapDemandRepository mapDemandRepository;

    public MapDemandService(MapDemandRepository mapDemandRepository) {
        this.mapDemandRepository = mapDemandRepository;
    }

    /**
     * Loads demand points after validating request parameters.
     */
    public List<MapDemandResponse> getMapDemand(
            String mode,
            String dayType,
            Integer hour,
            String line
    ) {

        validateMode(mode);
        validateDayType(dayType);
        validateHour(hour);

        return mapDemandRepository.findMapDemand(
                mode,
                dayType,
                hour,
                line
        );
    }

    /**
     * Validates supported transport mode.
     */
    private void validateMode(String mode) {
        if (!"bus".equals(mode) && !"subway".equals(mode)) {
            throw new IllegalArgumentException(
                    "mode must be either 'bus' or 'subway'"
            );
        }
    }

    /**
     * Validates day type.
     */
    private void validateDayType(String dayType) {
        if (dayType == null || dayType.isBlank()) {
            throw new IllegalArgumentException(
                    "dayType must not be blank"
            );
        }
    }

    /**
     * Validates hour range.
     */
    private void validateHour(Integer hour) {
        if (hour == null || hour < 0 || hour > 23) {
            throw new IllegalArgumentException(
                    "hour must be between 0 and 23"
            );
        }
    }
}
