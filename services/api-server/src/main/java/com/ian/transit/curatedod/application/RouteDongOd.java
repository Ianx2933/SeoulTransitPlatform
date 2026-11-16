package com.ian.transit.curatedod.application;

import com.ian.transit.curatedod.dto.RouteDongOdResponse.Flow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sums one route's stop-pair OD into district pairs. Pure: the caller supplies
 * the legs and which district each stop (by padded ARS number) lies in.
 */
public final class RouteDongOd {

    private RouteDongOd() {
    }

    /** ARS numbers as the stop tables key them: digits padded to five. */
    public static String padArs(String ars) {
        if (ars == null) {
            return null;
        }
        String trimmed = ars.trim();
        if (trimmed.matches("\\d{1,4}")) {
            return "0".repeat(5 - trimmed.length()) + trimmed;
        }
        return trimmed;
    }

    /** Totals per district and per district pair. */
    public record Result(
        Map<String, double[]> byDistrict,
        List<Flow> flows,
        double total,
        double unplaced
    ) {
    }

    /**
     * @param legs          the route's OD pairs
     * @param districtOfArs district code for each padded ARS number; stops
     *                      missing from the map are unplaced
     */
    public static Result aggregate(List<RouteOdAnalyzer.Leg> legs, Map<String, String> districtOfArs) {
        Map<String, double[]> byDistrict = new LinkedHashMap<>();
        Map<String, Double> pairs = new LinkedHashMap<>();
        double total = 0;
        double unplaced = 0;
        for (RouteOdAnalyzer.Leg leg : legs) {
            double p = leg.passengers();
            total += p;
            String from = districtOfArs.get(padArs(leg.boardArs()));
            String to = districtOfArs.get(padArs(leg.alightArs()));
            if (from == null || to == null) {
                unplaced += p;
                continue;
            }
            byDistrict.computeIfAbsent(from, k -> new double[2])[0] += p;
            byDistrict.computeIfAbsent(to, k -> new double[2])[1] += p;
            pairs.merge(from + "|" + to, p, Double::sum);
        }
        List<Flow> flows = new ArrayList<>(pairs.size());
        pairs.forEach((key, p) -> {
            int bar = key.indexOf('|');
            flows.add(new Flow(key.substring(0, bar), key.substring(bar + 1), p));
        });
        flows.sort(Comparator.comparingDouble(Flow::passengers).reversed());
        return new Result(byDistrict, flows, total, unplaced);
    }
}
