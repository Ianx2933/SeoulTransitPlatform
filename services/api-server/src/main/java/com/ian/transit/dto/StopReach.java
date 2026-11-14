package com.ian.transit.curatedod.dto;

/**
 * How far the riders on board after a stop carry: the farthest stop after which
 * more than {@code level} of them are still on board. Null when fewer than that
 * many are on board in the first place.
 */
public record StopReach(Double level, Integer untilSeq, String untilArs, String untilName) {
}
