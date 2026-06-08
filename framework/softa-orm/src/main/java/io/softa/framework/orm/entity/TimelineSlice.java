package io.softa.framework.orm.entity;

import java.io.Serializable;
import java.time.LocalDate;
import lombok.Data;

/**
 * The minimal attributes of timeline model slice.
 */
@Data
public class TimelineSlice {
    private Serializable id;
    private Serializable sliceId;
    private LocalDate effectiveStartDate;
    private LocalDate effectiveEndDate;
}
