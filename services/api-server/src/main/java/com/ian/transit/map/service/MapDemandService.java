package com.ian.transit.map.service;

import com.ian.transit.map.dto.DistrictDemandResponse;
import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.dto.NodeCatchmentDemandResponse;
import com.ian.transit.map.dto.NodeDemandDetailResponse;
import com.ian.transit.map.dto.NodeSearchResponse;
import com.ian.transit.map.repository.DistrictDemandRepository;
import com.ian.transit.map.repository.MapDemandRepository;
import com.ian.transit.map.repository.NodeCatchmentRepository;
import com.ian.transit.map.repository.NodeDemandRepository;
import com.ian.transit.map.repository.NodeSearchRepository;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Application service for map demand use cases.
 *
 * The service owns request parsing and validation so repositories can focus
 * on SQL construction, row mapping, and demand aggregation.
 */
@Service
public class MapDemandService {

    private static final int DEFAULT_NODE_SEARCH_LIMIT = 30;
    private static final int MAX_NODE_SEARCH_LIMIT = 100;

    /**
     * Day type values stored by the Phase 6.3 and 6.5 demand pipelines.
     *
     * Sunday and public holidays are merged into a single "sun_holiday" bucket,
     * so "sun" is not a valid value. An unrecognized day type must be rejected
     * here rather than passed to SQL, where it would match no rows and produce
     * zero demand indistinguishable from a genuinely empty result.
     *
     * Kept in sync with DAY_TYPES in pipelines/hourly_stop_pattern/common.py
     * and pipelines/subway_integration_light/common.py.
     */
    private static final List<String> ALLOWED_DAY_TYPES = List.of(
            "mon",
            "tue",
            "wed",
            "thu",
            "fri",
            "sat",
            "sun_holiday"
    );

    private final MapDemandRepository mapDemandRepository;
    private final DistrictDemandRepository districtDemandRepository;
    private final NodeDemandRepository nodeDemandRepository;
    private final NodeCatchmentRepository nodeCatchmentRepository;
    private final NodeSearchRepository nodeSearchRepository;

    public MapDemandService(
            MapDemandRepository mapDemandRepository,
            DistrictDemandRepository districtDemandRepository,
            NodeDemandRepository nodeDemandRepository,
            NodeCatchmentRepository nodeCatchmentRepository,
            NodeSearchRepository nodeSearchRepository
    ) {
        this.mapDemandRepository = mapDemandRepository;
        this.districtDemandRepository = districtDemandRepository;
        this.nodeDemandRepository = nodeDemandRepository;
        this.nodeCatchmentRepository = nodeCatchmentRepository;
        this.nodeSearchRepository = nodeSearchRepository;
    }

    /**
     * Loads route-selected map demand points.
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
     * Searches selectable coordinate-matched nodes.
     */
    public List<NodeSearchResponse> searchNodes(String keyword, Integer limit) {
        String normalizedKeyword = validateAndNormalizeKeyword(keyword);
        int normalizedLimit = normalizeSearchLimit(limit);

        return nodeSearchRepository.searchNodes(
                normalizedKeyword,
                normalizedLimit
        );
    }

    /**
     * Loads all-route demand for one selected stop or station.
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

        return nodeDemandRepository.findNodeDemandDetail(
                mode,
                nodeId,
                requestedDayTypes,
                dayAggregation,
                requestedHours
        );
    }

    /**
     * Loads all-route demand around a selected coordinate.
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

        return nodeCatchmentRepository.findNodeCatchmentDemand(
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
     * Loads district-centered all-route demand. (행정동 중심 전체 노선 수요를 불러옵니다.)
     */
    public DistrictDemandResponse getDistrictDemand(
            String districtCode,
            String districtCodes,
            String modes,
            String dayType,
            String dayTypes,
            String dayAggregation,
            Integer hour,
            String hours,
            Integer nodeLimit
    ) {
        validateDayAggregation(dayAggregation);
        validateNodeLimit(nodeLimit);

        List<String> requestedDistrictCodes = parseDistrictCodes(districtCode, districtCodes);
        List<String> requestedModes = parseModes(modes);
        requestedModes.forEach(this::validateMode);

        List<String> requestedDayTypes = parseDayTypes(dayType, dayTypes);
        List<Integer> requestedHours = parseHours(hour, hours);

        return districtDemandRepository.findDistrictDemand(
                requestedDistrictCodes,
                requestedModes,
                requestedDayTypes,
                dayAggregation,
                requestedHours,
                nodeLimit
        );
    }

    /**
     * Validates the optional district node-row limit. A null limit keeps the
     * full row set for backward compatibility; totals are unaffected either
     * way because truncation happens after aggregation.
     */
    private void validateNodeLimit(Integer nodeLimit) {
        if (nodeLimit != null && (nodeLimit < 1 || nodeLimit > 10000)) {
            throw new IllegalArgumentException("nodeLimit must be between 1 and 10000");
        }
    }

    /**
     * Parses selected administrative district codes.
     */
    private List<String> parseDistrictCodes(String districtCode, String districtCodes) {
        List<String> parsedDistrictCodes;

        if (districtCodes != null && !districtCodes.isBlank()) {
            parsedDistrictCodes = Arrays.stream(districtCodes.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        } else if (districtCode != null && !districtCode.isBlank()) {
            parsedDistrictCodes = List.of(districtCode.trim());
        } else {
            throw new IllegalArgumentException("districtCode or districtCodes must be provided");
        }

        if (parsedDistrictCodes.isEmpty()) {
            throw new IllegalArgumentException("at least one district code must be provided");
        }

        parsedDistrictCodes.forEach(this::validateDistrictCode);
        return parsedDistrictCodes;
    }

    /**
     * Validates administrative district code shape.
     */
    private void validateDistrictCode(String districtCode) {
        if (districtCode == null || !districtCode.matches("\\d{8,10}")) {
            throw new IllegalArgumentException("districtCode must contain 8 to 10 digits");
        }
    }

    /**
     * Validates supported transport mode.
     */
    private void validateMode(String mode) {
        if (!"bus".equals(mode) && !"subway".equals(mode)) {
            throw new IllegalArgumentException("mode must be either 'bus' or 'subway'");
        }
    }

    /**
     * Validates node identifier.
     */
    private void validateNodeId(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
    }

    /**
     * Validates coordinate bounds for the current service area.
     */
    private void validateCoordinate(double lat, double lng) {
        if (lat < 33.0 || lat > 39.5 || lng < 124.0 || lng > 132.5) {
            throw new IllegalArgumentException("coordinate is outside supported bounds");
        }
    }

    /**
     * Validates radius values used by the node catchment feature.
     */
    private void validateRadiusMeters(Integer radiusMeters) {
        if (radiusMeters == null ||
                (radiusMeters != 400 && radiusMeters != 800 && radiusMeters != 1000)) {
            throw new IllegalArgumentException("radiusMeters must be one of 400, 800, or 1000");
        }
    }

    /**
     * Validates and normalizes node search keyword.
     *
     * A two-character minimum is enforced because single-character searches
     * return overly broad results that the LIMIT-based pagination cannot
     * usefully narrow down. The error messages mention both Korean and
     * English so end users on the planned i18n service understand them.
     */
    private String validateAndNormalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException(
                    "keyword must not be blank (검색어가 비어 있습니다)"
            );
        }

        String trimmedKeyword = keyword.trim();

        if (trimmedKeyword.length() < 2) {
            throw new IllegalArgumentException(
                    "keyword must contain at least 2 characters " +
                    "(검색어는 최소 2글자 이상이어야 합니다)"
            );
        }

        return trimmedKeyword;
    }

    /**
     * Normalizes search result limit.
     */
    private int normalizeSearchLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_NODE_SEARCH_LIMIT;
        }

        if (limit < 1) {
            return DEFAULT_NODE_SEARCH_LIMIT;
        }

        return Math.min(limit, MAX_NODE_SEARCH_LIMIT);
    }

    /**
     * Parses selected modes.
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
     */
    private List<String> parseDayTypes(String dayType, String dayTypes) {
        List<String> parsedDayTypes;

        if (dayTypes != null && !dayTypes.isBlank()) {
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
     * Validates day type against the values the demand tables actually store.
     *
     * "sun" is rejected on purpose: Sunday demand is stored under
     * "sun_holiday", and silently returning zero for "sun" would read as an
     * absence of weekend demand rather than as a bad request.
     */
    private void validateDayType(String dayType) {
        if (dayType == null || dayType.isBlank()) {
            throw new IllegalArgumentException("dayType must not be blank");
        }

        if (!ALLOWED_DAY_TYPES.contains(dayType)) {
            throw new IllegalArgumentException(
                    "unknown dayType '" + dayType + "'; allowed values are "
                            + String.join(", ", ALLOWED_DAY_TYPES)
                            + " (Sunday demand is stored under 'sun_holiday')"
            );
        }
    }

    /**
     * Validates day aggregation mode.
     */
    private void validateDayAggregation(String dayAggregation) {
        if (!"sum".equals(dayAggregation) && !"average".equals(dayAggregation)) {
            throw new IllegalArgumentException("dayAggregation must be either 'sum' or 'average'");
        }
    }

    /**
     * Parses selected hours.
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
            throw new IllegalArgumentException("hour must be between 0 and 23");
        }
    }
}
