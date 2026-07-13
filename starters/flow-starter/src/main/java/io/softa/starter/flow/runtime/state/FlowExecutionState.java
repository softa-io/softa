package io.softa.starter.flow.runtime.state;

import java.time.LocalDateTime;
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
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowExecutionState")
public class FlowExecutionState {

    @Schema(description = "Runtime instance id")
    private String instanceId;

    @Schema(description = "Optimistic-lock version for persisted runtime state")
    private Integer version;

    @Schema(description = "Exact FlowBundle.id used when this instance was created (primary runtime key)")
    private Long bundleId;

    @Schema(description = "Source FlowDesign.id (stable flow handle)")
    private Long designId;

    @Schema(description = "Root flow code (display/logging only)")
    private String flowCode;

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

    @Schema(description = "Execution status")
    private FlowExecutionStatus status;

    @Schema(description = "Immutable trigger payload (the 'input' tier of the three-tier variable context)")
    private Map<String, Object> inputPayload;

    @Schema(description = "Execution variables (the 'vars' tier of the three-tier variable context)")
    private Map<String, Object> variables;

    @Schema(description = "Active waits (timer / async) pinned to the nodes that suspended them")
    private List<FlowWaitToken> waitTokens;

    @Schema(description = "Completed node ids")
    private List<String> completedNodeIds;

    @Schema(description = "Pending approvals")
    private List<PendingApproval> pendingApprovals;

    @Schema(description = "Returned approval that can be resubmitted by the initiator")
    private ReturnedApprovalContext returnedApproval;

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

    @Schema(description = "How many times the instance has been resubmitted after return")
    private Integer resubmissionCount;

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

    /** Whether the instance is still parked on any timer / async wait. */
    public boolean hasWaitTokens() {
        return waitTokens != null && !waitTokens.isEmpty();
    }

    /**
     * Earliest due time across TIMER waits, or {@code null} if none. Denormalized
     * onto {@code FlowInstance.nextFireAt} so the timer sweep can index it.
     */
    public LocalDateTime earliestTimerDueAt() {
        if (waitTokens == null) {
            return null;
        }
        return waitTokens.stream()
                .filter(t -> t.getType() == WaitType.TIMER && t.getDueAt() != null)
                .map(FlowWaitToken::getDueAt)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    /** TIMER waits whose due time is at or before {@code cutoff}. */
    public List<FlowWaitToken> dueTimerTokens(LocalDateTime cutoff) {
        if (waitTokens == null) {
            return List.of();
        }
        return waitTokens.stream()
                .filter(t -> t.getType() == WaitType.TIMER
                        && t.getDueAt() != null
                        && !t.getDueAt().isAfter(cutoff))
                .toList();
    }
}
