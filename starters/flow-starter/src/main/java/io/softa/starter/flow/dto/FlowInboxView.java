package io.softa.starter.flow.dto;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified inbox view that groups approval and CC tasks for one actor.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowInboxView")
public class FlowInboxView {

    @Schema(description = "Pending approval tasks assigned to the actor")
    private List<FlowApprovalTaskView> approvalPendingTasks;

    @Schema(description = "Unread CC tasks for the actor")
    private List<FlowApprovalTaskView> ccUnreadTasks;

    @Schema(description = "Read CC tasks for the actor")
    private List<FlowApprovalTaskView> ccReadTasks;

    @Schema(description = "Completed approval tasks for the actor when explicitly requested")
    private List<FlowApprovalTaskView> approvalCompletedTasks;
}

