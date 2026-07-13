package io.softa.starter.flow.runtime.spi;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.ApprovalTimeoutStrategy;

/**
 * Timeout configuration for an approval node.
 * Extracted from {@code CompiledFlowNode.config} when present.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "ApprovalTimeoutConfig")
public class ApprovalTimeoutConfig {

    @Schema(description = "Timeout duration in hours")
    private Integer timeoutHours;

    @Schema(description = "Strategy when the timeout is reached")
    private ApprovalTimeoutStrategy timeoutStrategy;

    @Schema(description = "Remind interval in hours before timeout")
    private Integer remindIntervalHours;

    @Schema(description = "Maximum number of reminders")
    private Integer maxRemindTimes;

    @Schema(description = "Escalation target user ID when strategy is ESCALATE")
    private String escalateToUserId;
}

