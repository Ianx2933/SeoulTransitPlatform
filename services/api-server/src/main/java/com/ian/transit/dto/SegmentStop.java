package com.ian.transit.curatedod.dto;

import com.ian.transit.common.TypicalDay;

/**
 * One stop inside a segment. leavingHere: riders boarding here who alight past
 * the segment end. leavingOnBoard: those riders accumulated from the segment
 * start, i.e. how many riders bound past the end are on board after this stop.
 */
public record SegmentStop(int seq, String ars, String name, Double boardings, Double alightings,
                          Double onBoardAfter, Double leavingHere, Double leavingOnBoard) {
    public SegmentStop {
        boardings = TypicalDay.round(boardings);
        alightings = TypicalDay.round(alightings);
        onBoardAfter = TypicalDay.round(onBoardAfter);
        leavingHere = TypicalDay.round(leavingHere);
        leavingOnBoard = TypicalDay.round(leavingOnBoard);
    }
}
