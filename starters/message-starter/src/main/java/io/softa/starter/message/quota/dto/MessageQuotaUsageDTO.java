package io.softa.starter.message.quota.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Current-month usage vs resolved limits for one quota bucket — the
 * operations view behind the quota page.
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
}
