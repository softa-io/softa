package io.softa.starter.flow.dto;

import java.time.LocalDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.runtime.state.AddSignPosition;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * API view of an approval record query result.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowApprovalRecordView")
public class FlowApprovalRecordView {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Runtime instance id")
    private String instanceId;

    @Schema(description = "Flow ID")
    private String flowId;

    @Schema(description = "Flow name")
    private String flowName;

    @Schema(description = "Published flow revision")
    private Integer flowRevision;

    @Schema(description = "Primary node id")
    private String nodeId;

    @Schema(description = "Primary node label")
    private String nodeLabel;

    @Schema(description = "Approval task cycle number associated with the record when applicable")
    private Integer cycleNumber;

    @Schema(description = "Task id when the record is attached to one task row")
    private Long taskId;

    @Schema(description = "Monotonic runtime sequence within one instance")
    private Integer sequence;

    @Schema(description = "Action type")
    private ApprovalActionType action;

    @Schema(description = "Operator actor id")
    private String actorId;

    @Schema(description = "The actor's name, on an instance's history; null when no one acted (an automatic approval)")
    private String actorName;

    @Schema(description = "Target actor id")
    private String targetActorId;

    @Schema(description = "The target actor's name, on an instance's history")
    private String targetActorName;

    @Schema(description = "Who did it on the actor's behalf: an administrator who reassigned the actor's task; "
            + "null when the actor acted themselves")
    private String operatorId;

    @Schema(description = "Add-sign position when the record represents an add-sign action")
    private AddSignPosition addSignPosition;

    @Schema(description = "Comment")
    private String comment;

    @Schema(description = "Status before action")
    private FlowExecutionStatus statusBefore;

    @Schema(description = "Status after action")
    private FlowExecutionStatus statusAfter;

    @Schema(description = "Actors who had already approved")
    private List<String> approvedActors;

    @Schema(description = "Actors who had already rejected")
    private List<String> rejectedActors;

    @Schema(description = "Recorded time")
    private LocalDateTime eventTime;
}

