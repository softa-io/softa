package io.softa.starter.flow.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.starter.flow.enums.RecordEffectStatus;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * One row of an instance list — the caller's own record approvals, or a monitoring view: the instance's
 * summary columns, what a record's approval kept of its record at submission, the steps it waits on now
 * with the approvers still to act, every user and employee named.
 */
@Data
@Schema(name = "FlowInstanceSummaryView")
public class FlowInstanceSummaryView {

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Runtime instance id")
    private String instanceId;

    @Schema(description = "Exact FlowBundle.id the instance runs on")
    private Long bundleId;

    @Schema(description = "Flow id")
    private String flowId;

    @Schema(description = "Flow name")
    private String flowName;

    @Schema(description = "The flow's scenario")
    private FlowScenario scenario;

    @Schema(description = "Published flow revision")
    private Integer flowRevision;

    @Schema(description = "Instance title")
    private String title;

    @Schema(description = "Related model name")
    private String modelName;

    @Schema(description = "Related row id")
    private String rowId;

    @Schema(description = "Initiator: the user who submitted the record, or started the run")
    private FlowRecordApprovalsView.Actor initiator;

    @Schema(description = "Whether the instance is its record's approval")
    private Boolean recordApproval;

    @Schema(description = "A record's approval: the employee the record is about, as at submission")
    private FlowRecordApprovalsView.Actor subject;

    @Schema(description = "A record's approval: the subject's department at submission")
    private Department subjectDept;

    @Schema(description = "A record's approval: the date the record takes effect, when its model has one")
    private LocalDate effectiveDate;

    @Schema(description = "A record's approval: the record's own number, when its model has one")
    private String recordCode;

    @Schema(description = "Execution status")
    private FlowExecutionStatus status;

    @Schema(description = "The steps the instance waits on now, each with the approvers still to act")
    private List<FlowRecordApprovalsView.PendingStep> currentSteps;

    @Schema(description = "A record's approval returned to its submitter that no later submission replaced: the "
            + "record still waits to be edited and submitted again")
    private Boolean awaitingRevision;

    @Schema(description = "The caller's own approval waiting on its approvers: whether they may withdraw it now, by "
            + "the flow's withdraw policy; null on any other row")
    private Boolean withdrawable;

    @Schema(description = "The caller's own approval waiting on its approvers: whether they may urge it now, by the "
            + "flow's urge settings, and if not, when; null on any other row. In the monitoring console's search, "
            + "every approval waiting on its approvers: whether the caller may urge it as an administrator")
    private FlowUrgeStatus urge;

    @Schema(description = "A record's approval whose record no longer exists — a draft deleted after its approval "
            + "was returned or withdrawn")
    private Boolean recordDeleted;

    @Schema(description = "A record's approval whose record was cancelled for good — a draft cancelled after its "
            + "approval was returned or withdrawn, which will not be submitted again")
    private Boolean recordCancelled;

    @Schema(description = "An approved record whose change takes effect later: applied, failed to apply, or still "
            + "scheduled, as the record says; null for a model whose approval takes effect at once")
    private RecordEffectStatus effectStatus;

    @Schema(description = "Node where execution failed (status = Failed)")
    private String failedNodeId;

    @Schema(description = "Earliest timer due time")
    private LocalDateTime nextFireAt;

    @Schema(description = "When the instance started — a record's approval: when the record was submitted")
    private LocalDateTime createdTime;

    @Schema(description = "When the instance last changed")
    private LocalDateTime updatedTime;

    /** A department, named. */
    @Data
    @Schema(name = "FlowInstanceSummaryDepartment")
    public static class Department {

        @Schema(description = "Department id")
        private Long id;

        @Schema(description = "Department name; null when it has none")
        private String name;

        public Department() {
        }

        public Department(Long id) {
            this.id = id;
        }
    }
}
