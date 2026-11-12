package io.softa.starter.message.quota.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * One quota bucket's usage vs its resolved limits — the month asked for, and
 * today — the operations view behind the quota page.
 */
@Data
@Schema(name = "MessageQuotaUsageDTO")
public class MessageQuotaUsageDTO {

    @Schema(description = "The quota bucket; -1 = the platform's own")
    private Long tenantId;

    @Schema(description = "Calendar month, yyyy-MM")
    private String month;

    @Schema(description = "Mail sends charged this month")
    private Long mailUsed;

    @Schema(description = "Resolved mail ceiling (row value, else deployment default); null = unlimited")
    private Long mailMonthlyLimit;

    @Schema(description = "SMS sends charged this month")
    private Long smsUsed;

    @Schema(description = "Resolved SMS ceiling (row value, else deployment default); null = unlimited")
    private Long smsMonthlyLimit;

    @Schema(description = "Mail sends charged today, whatever month is asked for")
    private Long mailUsedToday;

    @Schema(description = "Resolved daily mail cap (row value, else deployment default); null = unlimited")
    private Long mailDailyLimit;

    @Schema(description = "SMS sends charged today, whatever month is asked for")
    private Long smsUsedToday;

    @Schema(description = "Resolved daily SMS cap (row value, else deployment default); null = unlimited")
    private Long smsDailyLimit;
}
