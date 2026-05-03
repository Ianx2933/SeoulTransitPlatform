package com.ian.transit.curatedod.infrastructure;

import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Composite identifier for one OD relation within a route and date.
 * 
 * Date + route + boarding ARS + alighting ARS is used because OD data is naturally identified
 * by origin-destination pairs, not by a generated surrogate key.
 */

@NoArgsConstructor
@EqualsAndHashCode
public class CuratedOdRecordId implements Serializable {

    private String 기준일자;
    private String 노선명;
    private String 승차정류장ARS;
    private String 하차정류장ARS;
}
