package io.softa.starter.cron.message.dto;

import io.softa.framework.base.context.Context;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Cron task message.
 */
@Data
public class CronTaskMessage {

    private Long cronId;
    private String cronName;
    private LocalDateTime triggerTime;
    private LocalDateTime lastExecTime;

    private Context context;
}
