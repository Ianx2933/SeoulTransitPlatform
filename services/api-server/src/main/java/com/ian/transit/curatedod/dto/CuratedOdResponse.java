package com.ian.transit.curatedod.dto;

/**
 * Response DTO for curated OD records after correction and normalisation.
 *
 * This response intentionally exposes route, boarding stop, and alighting stop fields
 * because OD analysis depends on explicit origin-destination structure.
 */
public record CuratedOdResponse(
    String 기준일자,
    String 노선명,
    Long 전환노선ID,
    Integer 승차정류장순번,
    String 승차정류장ARS,
    String 승차정류장표준코드,
    String 승차정류장명,
    Integer 하차정류장순번,
    String 하차정류장ARS,
    String 하차정류장표준코드,
    String 하차정류장명,
    Integer 승객수
) {
}
