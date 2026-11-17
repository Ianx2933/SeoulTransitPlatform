package com.ian.transit.dongod.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DongAreaTest {

    @Test
    void parsesLevelsAndChecksCodes() {
        assertThat(DongArea.parse(null, DongArea.DONG)).isEqualTo(DongArea.DONG);
        assertThat(DongArea.parse("GU", DongArea.DONG)).isEqualTo(DongArea.GU);
        assertThatThrownBy(() -> DongArea.parse("si", DongArea.DONG)).isInstanceOf(IllegalArgumentException.class);

        DongArea.DONG.requireCode("11010530");
        DongArea.GU.requireCode("11010");
        DongArea.CITY.requireCode("11");
        assertThatThrownBy(() -> DongArea.DONG.requireCode("11010")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DongArea.GU.requireCode("11260")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DongArea.GU.requireCode("41135")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gusRangeCoversItsDistrictCodes() {
        String end = DongArea.codeEnd("11010");
        assertThat(end).isEqualTo("11011");
        assertThat("11010530".compareTo("11010") >= 0 && "11010530".compareTo(end) < 0).isTrue();
        assertThat("11020540".compareTo(end) < 0).isFalse();
    }

    @Test
    void namesUnitsAndKeepsOutside() {
        assertThat(DongArea.GU.nameOf("11230", null)).isEqualTo("강남구");
        assertThat(DongArea.CITY.nameOf("11", null)).isEqualTo("서울");
        assertThat(DongArea.DONG.nameOf("11010530", "사직동")).isEqualTo("사직동");
        assertThat(DongArea.GU.nameOf("outside", null)).isEqualTo("outside");
        assertThat(DongArea.GU.unitSql("origin_cd")).contains("LEFT(origin_cd, 5)");
        assertThat(DongArea.GU.filterSql("origin_cd")).isEqualTo("origin_cd >= :code AND origin_cd < :codeEnd");
    }
}
