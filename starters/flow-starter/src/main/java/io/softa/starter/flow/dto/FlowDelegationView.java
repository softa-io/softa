package io.softa.starter.flow.dto;

import java.time.LocalDateTime;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A delegation rule as the lists show it: its delegator and delegate named, not only their user ids.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowDelegationView")
public class FlowDelegationView {

    @Schema(description = "Rule id")
    private Long id;

    @Schema(description = "The approver whose tasks the rule routes, a user id")
    private String delegatorId;

    @Schema(description = "The delegator's name: their employee name, else their account's nickname or username")
    private String delegatorName;

    @Schema(description = "Whom the tasks go to, a user id")
    private String delegateId;

    @Schema(description = "The delegate's name, as the delegator's")
    private String delegateName;

    @Schema(description = "Why the tasks are delegated")
    private String reason;

    @Schema(description = "Which tasks: All, Flow or Node")
    private String scope;

    @Schema(description = "The flow, when the scope is flow- or node-specific")
    private String flowId;

    @Schema(description = "The flow's name")
    private String flowName;

    @Schema(description = "The node, when the scope is node-specific")
    private String nodeId;

    @Schema(description = "From when the rule applies; empty from its creation")
    private LocalDateTime startTime;

    @Schema(description = "Until when the rule applies; empty until it is cancelled")
    private LocalDateTime endTime;

    @Schema(description = "Whether the rule is in force")
    private Boolean active;

    @Schema(description = "Whether the rule switches itself off once its end time passes")
    private Boolean autoExpire;

    @Schema(description = "How many tasks the rule has routed")
    private Integer delegatedTaskCount;

    @Schema(description = "When the rule last routed a task")
    private LocalDateTime lastDelegationTime;

    @Schema(description = "When the rule was created")
    private LocalDateTime createdTime;
}
