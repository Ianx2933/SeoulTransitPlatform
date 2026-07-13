package com.ian.transit.map.dto;

import java.util.List;

/**
 * District-centered all-route demand response.
 *
 * The demand is node activity inside selected administrative polygons, not OD
 * flow between districts.
 *
 * unmatchedDistrictCodes lists requested codes that matched no boundary row,
 * so code-system mismatches surface instead of silently reading as zero
 * demand. totalNodeCount is the full route-node row count BEFORE any
 * nodeLimit truncation; totals and breakdowns are always computed from the
 * full row set.
 */
public record DistrictDemandResponse(
        List<DistrictSummary> districts,
        List<String> unmatchedDistrictCodes,
        long boarding,
        long alighting,
        long total,
        List<ModeDemand> modes,
        List<RouteDemand> routes,
        List<NodeDemand> nodes,
        long totalNodeCount
) {
    /** Selected administrative district summary. */
    public record DistrictSummary(
            String districtCode,
            String districtName
    ) {
    }

    /** Mode-level demand breakdown. */
    public record ModeDemand(
            String mode,
            long boarding,
            long alighting,
            long total
    ) {
    }

    /** Route-level demand breakdown inside the district. */
    public record RouteDemand(
            String mode,
            String serviceId,
            long boarding,
            long alighting,
            long total
    ) {
    }

    /** Route-node demand row inside the district. */
    public record NodeDemand(
            String mode,
            String serviceId,
            String nodeId,
            String nodeName,
            double lat,
            double lng,
            long boarding,
            long alighting,
            long total
    ) {
    }
}
