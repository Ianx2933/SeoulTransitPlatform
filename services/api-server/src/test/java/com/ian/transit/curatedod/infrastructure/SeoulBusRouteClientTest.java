package com.ian.transit.curatedod.infrastructure;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeoulBusRouteClientTest {

    private static byte[] xml(String header, String items) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ServiceResult><comMsgHeader/>"
                + "<msgHeader><headerCd>" + header + "</headerCd><headerMsg>m</headerMsg><itemCount>0</itemCount></msgHeader>"
                + "<msgBody>" + items + "</msgBody></ServiceResult>").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsItemListRows() {
        List<Map<String, String>> rows = SeoulBusRouteClient.parseItems(xml("0",
                "<itemList><busRouteId>100100052</busRouteId><busRouteNm>302</busRouteNm></itemList>"
                        + "<itemList><busRouteId>165000421</busRouteId><busRouteNm>1302</busRouteNm></itemList>"));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("busRouteId", "100100052").containsEntry("busRouteNm", "302");
    }

    @Test
    void noResultIsEmptyAndOtherCodesFail() {
        assertThat(SeoulBusRouteClient.parseItems(xml("4", ""))).isEmpty();
        assertThatThrownBy(() -> SeoulBusRouteClient.parseItems(xml("7", ""))).isInstanceOf(IllegalStateException.class);
    }
}
