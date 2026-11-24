package com.ian.transit.scenario.dto;

import java.util.List;

/**
 * Result of a route scenario. Riders are typical-day riders a day (or in the
 * chosen boarding hours), rounded to 0.1. road lists the road segments of the
 * routes in the scenario and every other segment whose load changed.
 */
public record ScenarioResponse(String label, String hours, Summary summary, List<RouteResult> routes,
                               List<RoadSegment> road, List<NewRoute> newRoutes, Assumptions assumptions) {

    /** moved: riders who change to a new or short-turn service; lost: riders who lose a cut section. */
    public record Summary(double moved, double newTransfers, double lost, double lostWithAlternative) {
    }

    /** kind: existing, short_turn, split, cut, new. Sequences are the route's stop numbers. */
    public record RouteResult(String route, String kind, String baseRoute, double before, double after,
                              Integer splitAt, Integer fromSeq, Integer toSeq,
                              double peakBefore, double peakAfter, List<Load> loads) {
    }

    /** Riders on board from stop seq to the next stop. */
    public record Load(int seq, String ars, double before, double after) {
    }

    /** path: [lat, lng] points; straight is true where no stored road path links the two stops. */
    public record RoadSegment(String fromArs, String toArs, double before, double after,
                              List<double[]> path, boolean straight) {
    }

    /** A drawn route: length on stored road paths where they exist, straight × 1.3 elsewhere. */
    public record NewRoute(String name, List<Stop> stops, double lengthKm, double straightKm,
                           double roundTripMin, Double headwayMin, Integer buses) {
    }

    public record Stop(String ars, String name, Double lat, Double lng) {
    }

    public record Assumptions(double speedKmh, String newRouteShare, double shortTurnShare, String notModelled) {
    }
}
