package com.ian.transit.map.service;

import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.repository.MapDemandRepository;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Application service for map demand use cases.
 *
 * The service owns request parsing and validation so the repository can focus
 * only on SQL construction.
 */
@Service
public class MapDemandService {

    private final MapDemandRepository mapDemandRepository;

    public MapDemandService(MapDemandRepository mapDemandRepository) {
        this.mapDemandRepository = mapDemandRepository;
    }

    /**
     * Loads demand points after validating and normalizing request parameters.
     *
     * Single-value parameters are kept for backward compatibility.
     * Multi-value parameters are used by the exploration dashboard.
     */
    public List<MapDemandResponse> getMapDemand(
            String mode,
            String dayType,
            Integer hour,
            String line,
            String hours,
            String lines
    ) {
        validateMode(mode);
        validateDayType(dayType);

        List<Integer> requestedHours = parseHours(hour, hours);
        List<String> requestedLines = parseLines(line, lines);

        return mapDemandRepository.findMapDemand(
                mode,
                dayType,
                requestedHours,
                requestedLines
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
     * Parses selected hours.
     *
     * The dashboard sends comma-separated hours such as "7,8,9".
     * The legacy single hour parameter is used when hours is omitted.
     */
    private List<Integer> parseHours(Integer hour, String hours) {
        List<Integer> parsedHours;

        if (hours != null && !hours.isBlank()) {
            parsedHours = Arrays.stream(hours.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(Integer::valueOf)
                    .toList();
        } else if (hour != null) {
            parsedHours = List.of(hour);
        } else {
            throw new IllegalArgumentException(
                    "hour or hours must be provided"
            );
        }

        parsedHours.forEach(this::validateHour);
        return parsedHours;
    }

    /**
     * Parses selected lines.
     *
     * The dashboard sends comma-separated lines such as "2호선,7호선".
     * Returning an empty list means the repository should not apply a line filter.
     */
    private List<String> parseLines(String line, String lines) {
        if (lines != null && !lines.isBlank()) {
            return Arrays.stream(lines.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        }

        if (line != null && !line.isBlank()) {
            return List.of(line.trim());
        }

        return List.of();
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
