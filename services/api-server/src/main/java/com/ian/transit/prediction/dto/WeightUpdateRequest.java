package com.ian.transit.prediction.dto;

/**
 * Request for updating hourly weight configuration.
 * 
 * This is kept separate from prediction requests because weight adjustment is
 * an operational tuning action, not a normal user prediction query.
 */
public record WeightUpdateRequest(String dayType, int hour, double weight) {
}
