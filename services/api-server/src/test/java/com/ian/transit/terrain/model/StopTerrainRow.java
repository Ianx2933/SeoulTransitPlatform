package com.ian.transit.terrain.model;

/** A measured stop and its deterministic boundary assignment. */
public record StopTerrainRow(
        String nodeId, String stopNo, String stopName,
        double longitude, double latitude, Double elevM, double slopePct,
        String dongCode, String dongName, String assignmentMethod,
        int boundaryCandidateCount
) {}
