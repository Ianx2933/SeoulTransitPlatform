package com.ian.transit.curatedod.dto;

/**
 * Route-level OD passenger summary
 * 
 * This summary is used to quickly identify high-demand routes before drilling down 
 * into stop-to-stop OD matrices.
 */
public record RouteOdSummaryResponse(
    String 기준일자,
    String 노선명,
    Long totalPassengers
) {
}
