package com.ian.transit.curatedod.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Typical-day route-level stop OD, mapped to od_typical_route_stop (2026-11).
 *
 * One row per typical day, route, boarding stop and alighting stop: the mean
 * daily passengers over the days of that type in the last twelve months, built
 * in BigQuery from every corrected TOPIS OD day and loaded by
 * pipelines/od_typical/load_typical_od.py. 기준일자 holds a label such as
 * typical_weekday, so the columns and key follow analysis_table_final and
 * {@link CuratedOdRecordId} serves as the key here too.
 *
 * 승객수 is a mean, so it is a real number. A pair absent on a day counts as 0
 * that day; daysSeen says on how many of nDays days it occurred.
 */
@Entity
@Table(name = "od_typical_route_stop")
@Getter
@NoArgsConstructor
@IdClass(CuratedOdRecordId.class)
public class TypicalOdRecord {

    @Id
    @Column(name = "기준일자")
    private String 기준일자;

    @Column(name = "day_type")
    private String dayType;

    @Id
    @Column(name = "노선명")
    private String 노선명;

    @Column(name = "전환_노선id")
    private Long 전환노선ID;

    @Column(name = "승차_정류장순번")
    private Integer 승차정류장순번;

    @Id
    @Column(name = "승차_정류장ars")
    private String 승차정류장ARS;

    @Column(name = "승차_정류장표준코드")
    private String 승차정류장표준코드;

    @Column(name = "승차_정류장명")
    private String 승차정류장명;

    @Column(name = "하차_정류장순번")
    private Integer 하차정류장순번;

    @Id
    @Column(name = "하차_정류장ars")
    private String 하차정류장ARS;

    @Column(name = "하차_정류장표준코드")
    private String 하차정류장표준코드;

    @Column(name = "하차_정류장명")
    private String 하차정류장명;

    @Column(name = "승객수")
    private Double 승객수;

    @Column(name = "n_days")
    private Integer nDays;

    @Column(name = "days_seen")
    private Integer daysSeen;

    @Column(name = "window_start")
    private LocalDate windowStart;

    @Column(name = "window_end")
    private LocalDate windowEnd;

    @Column(name = "source")
    private String source;
}
