package com.ian.transit.terrain.dto;

import com.ian.transit.terrain.model.StopTerrainRow;
import com.ian.transit.terrain.model.TerrainDataset;

/** A terrain screening observation, not a wheelchair-accessibility verdict. */
public record StopTerrainResponse(
        String nodeId, String stopNo, String stopName, Double elevationM,
        double slopePct, String dongName, String dongCode,
        double longitude, double latitude, String assignmentMethod,
        int boundaryCandidateCount, String datasetVersion, String boundaryBaseDate
) {
    public static StopTerrainResponse from(StopTerrainRow r, TerrainDataset d) {
        return new StopTerrainResponse(r.nodeId(), r.stopNo(), r.stopName(), r.elevM(),
                r.slopePct(), r.dongName(), r.dongCode(), r.longitude(), r.latitude(),
                r.assignmentMethod(), r.boundaryCandidateCount(), d.datasetId(),
                d.boundaryBaseDate());
    }
}
