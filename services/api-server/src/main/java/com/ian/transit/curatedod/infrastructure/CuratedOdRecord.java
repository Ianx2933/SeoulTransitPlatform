package com.ian.transit.curatedod.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Curated OD read model mapped directly to analysis_table_final.
 * 
 * This entity is intentionally used as a read model because the table is already corrected,
 * normalised, and optimised for API serving.
 * 
 * Field names follow the source schema to reduce mapping ambiguity during validation.
 */

@Entity
@Table(name = "analysis_table_final")
@Getter
@NoArgsConstructor
@IdClass(CuratedOdRecordId.class)
public class CuratedOdRecord {

    @Id
    @Column(name = "기준일자")
    private String 기준일자;

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
    private Integer 승객수;
}