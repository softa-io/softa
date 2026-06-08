package io.softa.starter.cron.message.dto;

import java.time.LocalDateTime;
import lombok.Data;

import io.softa.framework.base.context.Context;

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
