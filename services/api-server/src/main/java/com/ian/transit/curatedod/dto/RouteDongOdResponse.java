package com.ian.transit.curatedod.dto;

import com.ian.transit.common.TypicalDay;

import java.util.List;

/**
 * One route's typical-day OD grouped by administrative district (행정동):
 * each stop goes to the district whose boundary covers it, and stop pairs are
 * summed into district pairs. Only the riders of this route are counted, so
 * this is the route's share of travel between districts, not all travel.
 *
 * districts lists every district the route's stops fall in, with the riders
 * boarding and alighting there and a simplified boundary (GeoJSON) for the
 * map. flows has one row per boarding-to-alighting district pair, including
 * trips inside one district. Passengers whose stop has no coordinates or lies
 * outside every boundary are counted in unplacedPassengers.
 */
public record RouteDongOdResponse(
    String 기준일자,
    String 노선명,
    Integer nDays,
    Integer hour,
    Double totalPassengers,
    Double unplacedPassengers,
    List<District> districts,
    List<Flow> flows
) {
    public RouteDongOdResponse {
        totalPassengers = TypicalDay.round(totalPassengers);
        unplacedPassengers = TypicalDay.round(unplacedPassengers);
    }

    public record District(String code, String name, Double boardings, Double alightings, String geometry) {
        public District {
            boardings = TypicalDay.round(boardings);
            alightings = TypicalDay.round(alightings);
        }
    }

    public record Flow(String fromCode, String toCode, Double passengers) {
        public Flow {
            passengers = TypicalDay.round(passengers);
        }
    }
}
