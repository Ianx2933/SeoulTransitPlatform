package com.ian.transit.odcorrection.application;

import com.ian.transit.curatedod.infrastructure.CuratedOdRecord;
import com.ian.transit.odcorrection.infrastructure.OdCorrectionCommandRepository;
import com.ian.transit.odcorrection.infrastructure.OdCorrectionQueryRepository;
import com.ian.transit.odcorrection.infrastructure.StopMasterLookup;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StandardCodeCorrectionServiceTest {

    private CuratedOdRecord row(String route, String boardingArs, String alightingArs) {
        CuratedOdRecord row = mock(CuratedOdRecord.class);
        when(row.get노선명()).thenReturn(route);
        when(row.get승차정류장ARS()).thenReturn(boardingArs);
        when(row.get하차정류장ARS()).thenReturn(alightingArs);
        return row;
    }

    @Test
    void eachStopGetsItsOwnCodeAndUnresolvedStopsStayEmpty() {
        OdCorrectionQueryRepository query = mock(OdCorrectionQueryRepository.class);
        OdCorrectionCommandRepository command = mock(OdCorrectionCommandRepository.class);
        StopMasterLookup lookup = mock(StopMasterLookup.class);

        CuratedOdRecord seoul1 = row("100", "01041", "02006");
        CuratedOdRecord seoul2 = row("100", "01918", "02006");
        CuratedOdRecord gyeonggi = row("100", "61142", "02006");
        when(query.findBy기준일자And승차정류장표준코드IsNull("20251111")).thenReturn(List.of(seoul1, seoul2, gyeonggi));
        when(lookup.findStandardCodeByArs("01041")).thenReturn(Optional.of("101000043"));
        when(lookup.findStandardCodeByArs("01918")).thenReturn(Optional.of("100000400"));
        when(lookup.findStandardCodeByArs("61142")).thenReturn(Optional.empty());

        int fixed = new StandardCodeCorrectionService(query, command, lookup).fixNullStandardCodes("20251111");

        assertThat(fixed).isEqualTo(2);
        // Different stops get different codes: the old client returned one code for all.
        verify(command).update승차정류장표준코드("20251111", "100", "01041", "02006", "101000043");
        verify(command).update승차정류장표준코드("20251111", "100", "01918", "02006", "100000400");
        verify(command, never()).update승차정류장표준코드(eq("20251111"), eq("100"), eq("61142"), anyString(), anyString());
    }

    @Test
    void ambiguousArsResolvesToNothing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("13137"))).thenReturn(List.of("112000054", "113000478"));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("01918"))).thenReturn(List.of("100000400"));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("~"))).thenReturn(List.of());

        StopMasterLookup lookup = new StopMasterLookup(jdbc);

        assertThat(lookup.findStandardCodeByArs("13137")).isEmpty();
        assertThat(lookup.findStandardCodeByArs("01918")).contains("100000400");
        assertThat(lookup.findStandardCodeByArs("~")).isEmpty();
        assertThat(lookup.findStandardCodeByArs(" ")).isEmpty();
        assertThat(lookup.findStandardCodeByArs(null)).isEmpty();
    }
}
