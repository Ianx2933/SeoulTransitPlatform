package com.ian.transit.map.service;

import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.dto.NodeCatchmentDemandResponse;
import com.ian.transit.map.dto.NodeDemandDetailResponse;
import com.ian.transit.map.repository.MapDemandRepository;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Application service for map demand use cases.
 * (지도 수요 조회 유스케이스를 처리하는 애플리케이션 서비스입니다.)
 *
 * The service owns request parsing and validation so the repository can focus
 * on SQL construction and row mapping.
 * (Repository가 SQL 생성과 row mapping에 집중할 수 있도록 요청 파싱과 검증은 서비스가 담당합니다.)
 */
@Service
public class MapDemandService {

    private final MapDemandRepository mapDemandRepository;

    public MapDemandService(MapDemandRepository mapDemandRepository) {
        this.mapDemandRepository = mapDemandRepository;
    }

    /**
     * Loads route-selected map demand points.
     * (노선 선택 기반 지도 수요 지점을 불러옵니다.)
     */
    public List<MapDemandResponse> getMapDemand(
            String mode,
            String dayType,
            String dayTypes,
            String dayAggregation,
            Integer hour,
            String line,
            String hours,
            String lines
    ) {
        validateMode(mode);
        validateDayAggregation(dayAggregation);

        List<String> requestedDayTypes = parseDayTypes(dayType, dayTypes);
        List<Integer> requestedHours = parseHours(hour, hours);
        List<String> requestedLines = parseLines(line, lines);

        return mapDemandRepository.findMapDemand(
                mode,
                requestedDayTypes,
                dayAggregation,
                requestedHours,
                requestedLines
        );
    }

    /**
     * Loads all-route demand for one selected stop or station.
     * (선택한 하나의 정류장 또는 역에 대한 전체 노선 수요를 불러옵니다.)
     */
    public NodeDemandDetailResponse getNodeDemandDetail(
            String mode,
            String nodeId,
            String dayType,
            String dayTypes,
            String dayAggregation,
            Integer hour,
            String hours
    ) {
        validateMode(mode);
        validateNodeId(nodeId);
        validateDayAggregation(dayAggregation);

        List<String> requestedDayTypes = parseDayTypes(dayType, dayTypes);
        List<Integer> requestedHours = parseHours(hour, hours);

        return mapDemandRepository.findNodeDemandDetail(
                mode,
                nodeId,
                requestedDayTypes,
                dayAggregation,
                requestedHours
        );
    }

    /**
     * Loads all-route demand around a selected coordinate.
     * (선택 좌표 주변의 전체 노선 수요를 불러옵니다.)
     */
    public NodeCatchmentDemandResponse getNodeCatchmentDemand(
            double lat,
            double lng,
            Integer radiusMeters,
            String modes,
            String dayType,
            String dayTypes,
            String dayAggregation,
            Integer hour,
            String hours
    ) {
        validateCoordinate(lat, lng);
        validateRadiusMeters(radiusMeters);
        validateDayAggregation(dayAggregation);

        List<String> requestedModes = parseModes(modes);
        requestedModes.forEach(this::validateMode);

        List<String> requestedDayTypes = parseDayTypes(dayType, dayTypes);
        List<Integer> requestedHours = parseHours(hour, hours);

        return mapDemandRepository.findNodeCatchmentDemand(
                lat,
                lng,
                radiusMeters,
                requestedModes,
                requestedDayTypes,
                dayAggregation,
                requestedHours
        );
    }

    /**
     * Validates supported transport mode.
     * (지원하는 교통수단 모드인지 검증합니다.)
     */
    private void validateMode(String mode) {
        if (!"bus".equals(mode) && !"subway".equals(mode)) {
            throw new IllegalArgumentException("mode must be either 'bus' or 'subway'");
        }
    }

    /**
     * Validates node identifier.
     * (노드 식별자를 검증합니다.)
     */
    private void validateNodeId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
    }

    /**
     * Validates coordinate bounds for the current service area.
     * (현재 서비스 권역에 맞는 좌표 범위인지 검증합니다.)
     */
    private void validateCoordinate(double lat, double lng) {
        if (lat < 33.0 || lat > 39.5 || lng < 124.0 || lng > 132.5) {
            throw new IllegalArgumentException("coordinate is outside supported bounds");
        }
    }

    /**
     * Validates radius values used by the node catchment feature.
     * (노드 묶음 기능에서 사용하는 반경 값을 검증합니다.)
     */
    private void validateRadiusMeters(Integer radiusMeters) {
        if (radiusMeters == null ||
                (radiusMeters != 400 && radiusMeters != 800 && radiusMeters != 1000)) {
            throw new IllegalArgumentException("radiusMeters must be one of 400, 800, or 1000");
        }
    }

    /**
     * Parses selected modes.
     * (선택된 교통수단 모드를 파싱합니다.)
     */
    private List<String> parseModes(String modes) {
        if (modes == null || modes.isBlank()) {
            return List.of("subway", "bus");
        }

        List<String> parsedModes = Arrays.stream(modes.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        if (parsedModes.isEmpty()) {
            throw new IllegalArgumentException("at least one mode must be provided");
        }

        return parsedModes;
    }

    /**
     * Parses selected day types.
     * (선택된 요일 유형을 파싱합니다.)
     */
    private List<String> parseDayTypes(String dayType, String dayTypes) {
        List<String> parsedDayTypes;

        if (dayTypes != null && !dayTypes.isBlank()) {
            // distinct() is required: duplicate day types would corrupt the
            // dayAggregation=average divisor (see MapDemandRepository#getDayAggregationDivisor),
            // producing artificially low per-day values.
            // (중복 요일이 들어오면 average 분기의 나눗수가 부풀려져 평균이 낮게 계산되므로 distinct가 필수입니다.)
            parsedDayTypes = Arrays.stream(dayTypes.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        } else if (dayType != null && !dayType.isBlank()) {
            parsedDayTypes = List.of(dayType.trim());
        } else {
            throw new IllegalArgumentException("dayType or dayTypes must be provided");
        }

        if (parsedDayTypes.isEmpty()) {
            throw new IllegalArgumentException("at least one day type must be provided");
        }

        parsedDayTypes.forEach(this::validateDayType);
        return parsedDayTypes;
    }

    /**
     * Validates day type.
     * (요일 유형 값을 검증합니다.)
     */
    private void validateDayType(String dayType) {
        if (dayType == null || dayType.isBlank()) {
            throw new IllegalArgumentException("dayType must not be blank");
        }
    }

    /**
     * Validates day aggregation mode.
     * (요일 집계 방식을 검증합니다.)
     */
    private void validateDayAggregation(String dayAggregation) {
        if (!"sum".equals(dayAggregation) && !"average".equals(dayAggregation)) {
            throw new IllegalArgumentException("dayAggregation must be either 'sum' or 'average'");
        }
    }

    /**
     * Parses selected hours.
     * (선택된 시간대를 파싱합니다.)
     */
    private List<Integer> parseHours(Integer hour, String hours) {
        List<Integer> parsedHours;

        if (hours != null && !hours.isBlank()) {
            parsedHours = Arrays.stream(hours.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .map(Integer::valueOf)
                    .distinct()
                    .toList();
        } else if (hour != null) {
            parsedHours = List.of(hour);
        } else {
            throw new IllegalArgumentException("hour or hours must be provided");
        }

        parsedHours.forEach(this::validateHour);
        return parsedHours;
    }

    /**
     * Parses selected lines.
     * (선택된 노선을 파싱합니다.)
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
     * (시간대 범위를 검증합니다.)
     */
    private void validateHour(Integer hour) {
        if (hour == null || hour < 0 || hour > 23) {
            throw new IllegalArgumentException("hour must be between 0 and 23");
        }
    }
}
