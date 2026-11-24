package com.ian.transit.scenario.dto;

import java.util.List;

/**
 * A route scenario to evaluate. date is the day type (weekday, saturday,
 * sunday_holiday; default weekday), hours optional boarding hours ("8" or
 * "7-9"), speedKmh the assumed running speed for vehicle counts (default 15).
 */
public record ScenarioRequest(String date, String hours, Double speedKmh, List<Operation> operations) {

    /**
     * type: SHORT_TURN (route, fromSeq, toSeq, share), SPLIT (route, atSeq),
     * CUT (route, fromSeq, toSeq = the part kept), NEW_ROUTE (name, stops as
     * 5-digit ARS in riding order, share, headwayMin). Sequences are the
     * route analysis page's stop numbers.
     */
    public record Operation(String type, String route, Integer fromSeq, Integer toSeq, Integer atSeq,
                            Double share, String name, List<String> stops, Double headwayMin) {
    }
}
