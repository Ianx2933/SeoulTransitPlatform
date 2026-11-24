package com.ian.transit.curatedod.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ian.transit.common.exception.NotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Names from the 2026-11-24 comparison of the boarding data with the representative OD. */
class RouteNameResolverTest {

    private static final List<String> OD = List.of(
            "110A고려대", "110B국민대", "143", "5522A난곡", "5522B호암", "8333",
            "8773출근", "8773퇴근", "서대문02대", "서대문02소", "한강버스01");

    @Test
    void anExactNameStaysAsItIs() {
        assertThat(RouteNameResolver.resolve("143", OD)).isEqualTo("143");
        assertThat(RouteNameResolver.resolve(" 5522A난곡 ", OD)).isEqualTo("5522A난곡");
    }

    @Test
    void theTerminusSuffixIsFoundWhenItIsTheOnlyOne() {
        assertThat(RouteNameResolver.resolve("5522A", OD)).isEqualTo("5522A난곡");
        assertThat(RouteNameResolver.resolve("110B", OD)).isEqualTo("110B국민대");
    }

    @Test
    void aTrailingLetterIsDroppedWhenTheOdJoinsTheVariants() {
        assertThat(RouteNameResolver.resolve("8333A", OD)).isEqualTo("8333");
        assertThat(RouteNameResolver.resolve("8333B", OD)).isEqualTo("8333");
    }

    @Test
    void aRouteSplitInTwoListsBothInsteadOfGuessing() {
        assertThatThrownBy(() -> RouteNameResolver.resolve("서대문02", OD))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("서대문02대")
                .hasMessageContaining("서대문02소");
    }

    @Test
    void aNumberOnlyPrefixDoesNotMatchAnotherRoute() {
        // "552" must not become 5522A난곡: the next character is a digit, not Hangul.
        assertThat(RouteNameResolver.resolve("552", OD)).isEqualTo("552");
    }

    @Test
    void theRushHourRunsAreListedForTheBareNumber() {
        assertThatThrownBy(() -> RouteNameResolver.resolve("8773", OD))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("8773출근")
                .hasMessageContaining("8773퇴근");
    }

    @Test
    void anUnknownNameIsReturnedForTheCallersNotFound() {
        assertThat(RouteNameResolver.resolve("서울01", OD)).isEqualTo("서울01");
    }
}
