package com.ian.transit.curatedod.dto;

/**
 * Stop-to-stop OD matrix response
 */
public record StopOdMatrixResponse(
    String 기준일자,
    String 노선명,
    String 승차정류장명,
    String 승차정류장ARS,
    String 하차정류장명,
    String 하차정류장ARS,
    Long passengers
) {
}
