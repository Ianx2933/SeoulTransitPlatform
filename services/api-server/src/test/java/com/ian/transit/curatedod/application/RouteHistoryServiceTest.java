package com.ian.transit.curatedod.application;

import com.ian.transit.curatedod.infrastructure.RouteSnapshotRepository.Seen;
import com.ian.transit.curatedod.infrastructure.RouteSnapshotRepository.VersionRow;
import com.ian.transit.curatedod.infrastructure.StoredRoutePathRepository.StationRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteHistoryServiceTest {

    private static final LocalDate JAN = LocalDate.of(2024, 1, 1);
    private static final LocalDate APR = LocalDate.of(2024, 4, 1);
    private static final LocalDate NOV = LocalDate.of(2026, 11, 2);

    private static List<VersionRow> version(LocalDate d, String... ars) {
        List<VersionRow> out = new ArrayList<>();
        for (int i = 0; i < ars.length; i++) {
            out.add(new VersionRow(d, "100100223", i + 1, ars[i], "s" + ars[i]));
        }
        return out;
    }

    @Test
    void changesListAddedAndRemovedStops() {
        List<VersionRow> rows = new ArrayList<>(version(JAN, "25341", "23201", "23202", "25317"));
        rows.addAll(version(NOV, "25341", "23290", "25317"));

        var h = RouteHistoryService.build("3411", rows, new Seen(JAN, NOV, NOV), List.of());

        assertThat(h.entries()).extracting(RouteHistoryService.Entry::kind).containsExactly("first", "changed");
        var change = h.entries().get(1);
        assertThat(change.date()).isEqualTo(NOV);
        assertThat(change.since()).isEqualTo(JAN);
        assertThat(change.added()).extracting(RouteHistoryService.StopRef::ars).containsExactly("23290");
        assertThat(change.removed()).extracting(RouteHistoryService.StopRef::ars).containsExactly("23201", "23202");
    }

    @Test
    void aRouteMissingFromLaterSnapshotsEnded() {
        var h = RouteHistoryService.build("1155", version(JAN, "42106", "11155"), new Seen(JAN, APR, NOV), List.of());

        assertThat(h.entries()).extracting(RouteHistoryService.Entry::kind).containsExactly("first", "ended");
        assertThat(h.entries().get(1).date()).isEqualTo(APR);
    }

    @Test
    void todaysStoredPathDifferingFromTheNewestSnapshot() {
        var today = List.of(
                new StationRow("3411", "100100223", 1, "25341", "a", 37.5, 127.1, false),
                new StationRow("3411", "100100223", 2, "23290", "b", 37.5, 127.1, false));

        var h = RouteHistoryService.build("3411", version(JAN, "25341", "23201"), new Seen(JAN, JAN, JAN), today);

        assertThat(h.entries()).extracting(RouteHistoryService.Entry::kind).containsExactly("first", "today");
        assertThat(h.entries().get(1).since()).isEqualTo(JAN);
    }

    @Test
    void sameStopsInAnotherOrderIsAReorder() {
        List<VersionRow> rows = new ArrayList<>(version(JAN, "01001", "01002"));
        rows.addAll(version(APR, "01002", "01001"));

        var h = RouteHistoryService.build("x", rows, null, List.of());

        assertThat(h.entries().get(1).reordered()).isTrue();
    }
}
