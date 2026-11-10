package io.softa.starter.flow.runtime.state;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Serializable execution state for the in-memory runtime engine.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowExecutionState")
public class FlowExecutionState {

    @Schema(description = "Runtime instance id")
    private String instanceId;

    @Schema(description = "Optimistic-lock version for persisted runtime state")
    private Integer version;

    /**
     * The id of the FlowInstance row the state was read from or first saved as; null until then. A save updates that
     * row by it, without reading the row first. Not part of the API.
     */
    @JsonIgnore
    @Schema(hidden = true)
    private Long persistedId;

    @Schema(description = "Exact FlowBundle.id used when this instance was created (primary runtime key)")
    private Long bundleId;

    @Schema(description = "Source FlowDesign.id (stable flow handle)")
    private String flowId;

    @Schema(description = "Flow name (display only)")
    private String flowName;

    @Schema(description = "Resolved published flow revision (display/logging only)")
    private Integer flowRevision;

    @Schema(description = "Instance title")
    private String title;

    @Schema(description = "Related model name")
    private String modelName;

    @Schema(description = "Related row data ID")
    private String rowId;

    @Schema(description = "Flow initiator id")
    private String initiatorId;

    /** Not stored: the initiator named, filled in when the instance detail is served. */
    @Schema(description = "The initiator's name, on the instance detail", accessMode = Schema.AccessMode.READ_ONLY)
    private String initiatorName;

    @Schema(description = "Whether the instance is the approval of its record: started by submitting the record, "
            + "so its result is written back to it. Other instances running on a record are automation runs")
    private Boolean recordApproval;

    @Schema(description = "A record's approval: the employee the record is about, as at submission")
    private Long subjectId;

    @Schema(description = "A record's approval: the subject's department at submission")
    private Long departmentId;

    @Schema(description = "A record's approval: the date the record takes effect, when its model has one")
    private LocalDate effectiveDate;

    @Schema(description = "A record's approval: the record's own number, when its model has one")
    private String recordCode;

    @Schema(description = "A record's approval: a line saying what the record asks for, as its handler words it")
    private String summary;

    @Schema(description = "Identity of the trigger event that started the instance (a change log's uuid); with "
            + "the flow, an event starts a run at most once")
    private String triggerEventId;

    @Schema(description = "Execution status")
    private FlowExecutionStatus status;

    @Schema(description = "Execution variables (the 'vars' tier of the three-tier variable context)")
    private Map<String, Object> variables;

    @Schema(description = "AsyncTask nodes waiting for their callback")
    private List<FlowWaitToken> waitTokens;

    @Schema(description = "Completed node ids")
    private List<String> completedNodeIds;

    @Schema(description = "Pending approvals")
    private List<PendingApproval> pendingApprovals;

    /**
     * Approval audit delta of the CURRENT attempt only. The authoritative history lives in
     * {@code flow_approval_record}: the instance store flushes this buffer on save (sequence
     * continuing from {@link #auditSequenceBase}) and readers merge committed rows with the
     * unflushed tail. Excluded from JSON / persistence.
     */
    @JsonIgnore
    @Schema(hidden = true)
    private List<ApprovalActionAuditEntry> approvalAuditDelta;

    /**
     * Internal high-water mark: number of {@link #approvalAuditDelta} entries already
     * flushed to {@code flow_approval_record}. Managed by the ledger flush.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @Builder.Default
    private int persistedAuditCount = 0;

    /**
     * Sequence base for {@link #approvalAuditDelta} entry 0 — the number of audit rows the
     * instance already has from prior attempts. {@code -1} = not yet known; resolved lazily
     * (one COUNT) on the first flush of a loaded state. Fresh instances start at {@code 0}.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @Builder.Default
    private int auditSequenceBase = 0;

    /**
     * The instance's committed audit rows as this action read them, with what it flushed since — so an action reads
     * the ledger once ({@code ApprovalAuditReader}). {@code null} = not read yet; a new instance starts it empty.
     * Excluded from JSON / persistence, as the delta is.
     */
    @JsonIgnore
    @Schema(hidden = true)
    private List<ApprovalActionAuditEntry> committedAudit;

    @Schema(description = "Parallel join arrival counts")
    private Map<String, Integer> joinArrivalCounts;

    @Schema(description = "Execution trace (in-memory delta of the current attempt; persisted to flow_execution_trace)")
    private List<FlowExecutionTraceEntry> trace;

    /**
     * Internal high-water mark: number of {@link #trace} entries already persisted
     * to {@code flow_execution_trace}. Excluded from JSON / persistence; managed by
     * the trace flush in {@code FlowExecutionTraceService.appendNewEntries}.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @Builder.Default
    private int persistedTraceCount = 0;

    /**
     * Sequence base for {@link #trace} entry 0 — the number of trace rows the instance
     * already has in {@code flow_execution_trace} from prior attempts. Loads leave the
     * in-memory trace empty (a delta buffer), so persisted sequence numbers must continue
     * from this base instead of restarting at the list index. {@code -1} = not yet known;
     * resolved lazily (one COUNT) on the first flush of a loaded state. Fresh instances
     * start at {@code 0}. Excluded from JSON / persistence.
     */
    @JsonIgnore
    @Schema(hidden = true)
    @Builder.Default
    private int traceSequenceBase = 0;

    @Schema(description = "Return data from ReturnValue nodes for synchronous flow result")
    private Object returnData;

    @Schema(description = "Error message")
    private String errorMessage;

    @Schema(description = "Node where execution failed (set when status = Failed)")
    private String failedNodeId;

    /**
     * The approval step after which a failed approval runs again: set when the steps after that step failed — the
     * approval kept, their work rolled back — and cleared when they run through. Only an instance that has one can
     * be retried.
     */
    @Schema(description = "Approval step after which a failed approval runs again on retry")
    private String retryAfterNodeId;

    // ── Wait-token helpers (null-safe) ─────────────────────────────────────────

    /** Register a new wait, initializing the list on first use. */
    public void addWaitToken(FlowWaitToken token) {
        if (waitTokens == null) {
            waitTokens = new ArrayList<>();
        }
        waitTokens.add(token);
    }

    /** The live wait pinned to {@code nodeId}, or {@code null} if none (already consumed). */
    public FlowWaitToken findWaitToken(String nodeId) {
        if (waitTokens == null || nodeId == null) {
            return null;
        }
        return waitTokens.stream()
                .filter(t -> nodeId.equals(t.getNodeId()))
                .findFirst()
                .orElse(null);
    }

    /** Remove the wait pinned to {@code nodeId} once its branch resumes. */
    public void removeWaitToken(String nodeId) {
        if (waitTokens != null && nodeId != null) {
            waitTokens.removeIf(t -> nodeId.equals(t.getNodeId()));
        }
    }

    /** Whether the instance is still parked on an async task. */
    public boolean hasWaitTokens() {
        return waitTokens != null && !waitTokens.isEmpty();
    }

    /** Whether this instance is its record's approval (see {@link #recordApproval}). */
    @JsonIgnore
    public boolean isApprovalOfRecord() {
        return Boolean.TRUE.equals(recordApproval);
    }

}
