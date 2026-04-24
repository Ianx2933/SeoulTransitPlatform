package com.ian.transit.curatedod.infrastructure;

import java.io.Serializable;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Composite identifier for CuratedOdRecord.
 */

@NoArgsConstructor
@EqualsAndHashCode
public class CuratedOdRecordId implements Serializable {

    private String 기준일자;
    private String 노선명;
    private String 승차정류장ARS;
    private String 하차정류장ARS;
}
