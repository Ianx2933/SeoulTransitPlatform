package com.ian.transit.curatedod.dto;

/**
 * Stop-to-stop OD matrix response
 * 
 * This response keeps origin and destination stop identifiers together so that
 * downstream map or table views can reconstruct passenger flows.
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
