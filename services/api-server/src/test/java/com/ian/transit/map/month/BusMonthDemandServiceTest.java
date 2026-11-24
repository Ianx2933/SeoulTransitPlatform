package com.ian.transit.map.month;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.map.dto.MapDemandResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BusMonthDemandServiceTest {

    /** A repository without BigQuery or a database: fixed rows and stop positions. */
    static class FakeRepository extends BusMonthDemandRepository {
        int calls;
        List<StopMonthDemand> rows = List.of(
                new StopMonthDemand("143", "01001", 300, 150),
                new StopMonthDemand("143", "09999", 10, 10));

        FakeRepository() {
            super(null, null);
        }

        @Override
        public List<StopMonthDemand> demand(int serviceYm, Collection<String> dayTypes, Collection<Integer> hours,
                                            Collection<String> routes) {
            calls++;
            return rows;
        }

        @Override
        public Map<String, StopPoint> stopPoints(Collection<String> paddedArs) {
            return Map.of("01001", new StopPoint("01001", "종로1가", 37.57, 126.98));
        }

        @Override
        public List<Integer> months() {
            return List.of(202401, 202610);
        }
    }

    @Test
    void aMeanDividesByTheDayTypesAndStopsWithoutAPositionAreLeftOut() {
        var repo = new FakeRepository();
        var service = new BusMonthDemandService(repo);
        List<MapDemandResponse> out = service.demand("2026-03", "mon,tue,wed", "average", "8,9", "143");
        assertThat(out).hasSize(1);
        assertThat(out.get(0).boarding()).isEqualTo(100L);
        assertThat(out.get(0).alighting()).isEqualTo(50L);
        assertThat(out.get(0).nodeName()).isEqualTo("종로1가");

        List<MapDemandResponse> sum = service.demand("2026-03", "mon,tue,wed", "sum", "8,9", "143");
        assertThat(sum.get(0).boarding()).isEqualTo(300L);
    }

    @Test
    void theSameRequestIsAnsweredFromTheCache() {
        var repo = new FakeRepository();
        var service = new BusMonthDemandService(repo);
        service.demand("2026-03", "mon", "average", "8", "143");
        service.demand("2026-03", "mon", "average", "8", "143");
        assertThat(repo.calls).isEqualTo(1);
    }

    @Test
    void monthsAreShownAsYearMonth() {
        assertThat(new BusMonthDemandService(new FakeRepository()).months()).containsExactly("2024-01", "2026-10");
    }

    @Test
    void badInputIsRefusedAndAnEmptyMonthIsNotFound() {
        var repo = new FakeRepository();
        var service = new BusMonthDemandService(repo);
        assertThatThrownBy(() -> service.demand("2026-3", "mon", "average", "8", "143"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.demand("2026-03", "sun", "average", "8", "143"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.demand("2026-03", "mon", "average", "24", "143"))
                .isInstanceOf(IllegalArgumentException.class);
        repo.rows = List.of();
        assertThatThrownBy(() -> service.demand("2025-03", "mon", "average", "8", "143"))
                .isInstanceOf(NotFoundException.class);
    }
}
