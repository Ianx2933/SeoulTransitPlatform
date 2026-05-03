package com.ian.transit.congestion.application;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.congestion.dto.CongestionResponse;
import com.ian.transit.congestion.dto.SectionCongestionResponse;
import com.ian.transit.congestion.infrastructure.CongestionQueryRepository;
import com.ian.transit.congestion.model.CongestionLevel;
import com.ian.transit.congestion.model.CongestionRow;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Application service that orchestrates congestion use cases.
 * 
 * SQL queries stay in the repository, while this service converts raw query rows into
 * API responses and applies business-level congestion rules. 
 */

@Service
@RequiredArgsConstructor
public class CongestionQueryService {

    private final CongestionQueryRepository congestionQueryRepository;
    private final OccupancyCalculator occupancyCalculator;

    /**
     * Gets route congestion and converts raw query rows into response DTOs.
     * Throws NotFoundException when route/date has no data.
     */
    @Cacheable(value = "congestion", key = "#routeName.trim() + '_' + #date.trim()")
    public List<CongestionResponse> getRouteCongestion(String routeName, String date) {
        String normalizedRouteName = normalizeRequired(routeName, "routeName");
        String normalizedDate = normalizeRequired(date, "date");

        List<CongestionResponse> responses = congestionQueryRepository
                .findRouteCongestion(normalizedRouteName, normalizedDate)
                .stream()
                .map(this::toResponse)
                .toList();

        if (responses.isEmpty()) {
            throw new NotFoundException(
                    "No congestion data found for routeName=" + normalizedRouteName + ", date=" + normalizedDate
            );
        }

        return responses;
    }

    /**
     * Gets congestion for multiple routes and flattens the results into one list.
     */
    public List<CongestionResponse> getRoutesCongestion(List<String> routeNames, String date) {
        if (routeNames == null || routeNames.isEmpty()) {
            throw new IllegalArgumentException("routes must not be empty");
        }

        String normalizedDate = normalizeRequired(date, "date");

        List<CongestionResponse> responses = routeNames.stream()
                .map(this::normalizeOptional)
                .filter(routeName -> !routeName.isBlank())
                .flatMap(routeName -> getRouteCongestion(routeName, normalizedDate).stream())
                .toList();

        if (responses.isEmpty()) {
            throw new NotFoundException("No congestion data found for requested routes");
        }

        return responses;
    }

    /**
     * Gets ordered stop names for a route.
     */
    @Cacheable(value = "congestionStops", key = "#routeName.trim() + '_' + #date.trim()")
    public List<String> getStopsByRoute(String routeName, String date) {
        String normalizedRouteName = normalizeRequired(routeName, "routeName");
        String normalizedDate = normalizeRequired(date, "date");

        List<String> stops = congestionQueryRepository.findStopsByRoute(normalizedRouteName, normalizedDate);

        if (stops.isEmpty()) {
            throw new NotFoundException(
                    "No stops found for routeName=" + normalizedRouteName + ", date=" + normalizedDate
            );
        }

        return stops;
    }

    /**
     * Extracts congestion information from a start stop to an end stop.
     */
    public SectionCongestionResponse getSectionCongestion(
            String routeName,
            String from,
            String to,
            String date
    ) {
        String normalizedRouteName = normalizeRequired(routeName, "routeName");
        String normalizedFrom = normalizeRequired(from, "from");
        String normalizedTo = normalizeRequired(to, "to");
        String normalizedDate = normalizeRequired(date, "date");

        List<CongestionResponse> allStops = getRouteCongestion(normalizedRouteName, normalizedDate);
        List<CongestionResponse> sectionStops = extractSection(allStops, normalizedFrom, normalizedTo);

        if (sectionStops.isEmpty()) {
            throw new NotFoundException(
                    "No section found for routeName=" + normalizedRouteName
                            + ", from=" + normalizedFrom
                            + ", to=" + normalizedTo
                            + ", date=" + normalizedDate
            );
        }

        return new SectionCongestionResponse(
                normalizedRouteName,
                normalizedDate,
                normalizedFrom,
                normalizedTo,
                sectionStops
        );
    }

    private CongestionResponse toResponse(CongestionRow row) {
        double relativeCongestion = occupancyCalculator.calculateRelativeCongestion(
                row.occupancy(),
                row.maxOccupancy()
        );

        CongestionLevel congestionLevel = occupancyCalculator.classify(relativeCongestion);
        String congestionColor = occupancyCalculator.gradientColor(relativeCongestion);

        return new CongestionResponse(
                row.routeName(),
                row.sequence(),
                row.ars(),
                row.stopName(),
                row.occupancy(),
                row.maxOccupancy(),
                relativeCongestion,
                congestionLevel.name(),
                congestionColor,
                row.latitude(),
                row.longitude(),
                row.totalPassengers()
        );
    }


    /**
     * Extracts congestion information from a start stop to an end stop.
     * 
     * Section extraction currently uses stop names because the public UI is user-friendly.
     * 
     * For production-grade precision, ARS or sequence-based selection should be added later.
     */

    private List<CongestionResponse> extractSection(
            List<CongestionResponse> allStops,
            String from,
            String to
    ) {
        List<CongestionResponse> section = new ArrayList<>();
        boolean started = false;

        for (CongestionResponse stop : allStops) {
            if (stop.stopName().equals(from)) {
                started = true;
            }

            if (started) {
                section.add(stop);
            }

            if (started && stop.stopName().equals(to)) {
                return section;
            }
        }

        return section;
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalizedValue = normalizeOptional(value);

        if (normalizedValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return normalizedValue;
    }

    private String normalizeOptional(String value) {
        return value == null ? "" : value.trim();
    }
}
