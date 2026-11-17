package com.ian.transit.dongod.dto;

import com.ian.transit.common.TypicalDay;

import java.util.List;

/**
 * Bus routes that riders board inside one district (행정동), from the
 * typical-day route OD, with the districts those riders alight in.
 * unplacedPassengers counts riders whose alighting stop has no district.
 */
public record DongRoutesResponse(
    String code,
    String dayType,
    Double boardings,
    Double unplacedPassengers,
    List<Route> routes
) {
    public DongRoutesResponse {
        boardings = TypicalDay.round(boardings);
        unplacedPassengers = TypicalDay.round(unplacedPassengers);
    }

    public record Route(String route, Double boardings, List<Destination> destinations) {
        public Route {
            boardings = TypicalDay.round(boardings);
        }
    }

    public record Destination(String code, String name, Double passengers) {
        public Destination {
            passengers = TypicalDay.round(passengers);
        }
    }
}
