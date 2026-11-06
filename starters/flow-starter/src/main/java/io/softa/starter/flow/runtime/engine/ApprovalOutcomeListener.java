package io.softa.starter.flow.runtime.engine;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.enums.ApprovalOutcomeType;
import io.softa.starter.flow.runtime.spi.ApprovalOutcome;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Writes the result of a submitted record's approval back to the record, through
 * {@link ApprovalStatusWriter}, which then hands it to its model's {@link ModelApprovalHandler}.
 * The single place results reach business records: it runs inside the action that produced the
 * result — the same transaction, so a handler that throws vetoes the action — with permission checks
 * skipped, since approvers rarely hold update rights on the records they decide on and the engine has
 * already authorized them.
 *
 * <p>Only a record's approval — the instance its submission started — is written back: other
 * instances running on the same record (automation runs) are not its
 * approval. A failed instance writes nothing; its submitter takes the record back by withdrawing it.
 *
 * <p>An instance that already has its result is saved again when someone comments on it or reads
 * a CC. Such a save starts and ends in the same status and is not handed on. A save without audit
 * entries (a resumed async step) cannot be told apart, so the writer takes a result only onto a record
 * still in approval.
 */
@Slf4j
@Component
public class ApprovalOutcomeListener implements FlowStateChangeListener {

    private final ModelApprovalHandlerRegistry handlerRegistry;
    private final ApprovalAuditReader auditReader;
    private final ApprovalStatusWriter statusWriter;

    public ApprovalOutcomeListener(ModelApprovalHandlerRegistry handlerRegistry,
                                   ApprovalAuditReader auditReader,
                                   ApprovalStatusWriter statusWriter) {
        this.handlerRegistry = handlerRegistry;
        this.auditReader = auditReader;
        this.statusWriter = statusWriter;
    }

    @Override
    public void onStateChanged(FlowExecutionState state) {
        ApprovalOutcomeType type = outcomeOf(state.getStatus());
        if (type == null || !state.isApprovalOfRecord() || !StringUtils.hasText(state.getModelName())
                || !StringUtils.hasText(state.getRowId()) || alreadyInThisStatus(state)) {
            return;
        }
        Optional<ModelApprovalHandler<?>> handler = handlerRegistry.find(state.getModelName());
        if (handler.isEmpty()) {
            log.warn("Approval instance {} of {} {} ended {} but the model has no approval handler; "
                    + "nothing was written back", state.getInstanceId(), state.getModelName(), state.getRowId(), type);
            return;
        }
        ApprovalActionAuditEntry deciding = decidingAction(state, type);
        // An automatic approval's note says why the engine approved, not what anyone said.
        String comment = deciding == null || ApprovalActionType.AUTO_APPROVE.equals(deciding.getAction())
                ? null : deciding.getComment();
        ApprovalOutcome outcome = new ApprovalOutcome(type, state.getInstanceId(),
                deciding == null ? null : deciding.getActorId(), comment);
        statusWriter.concluded(handler.get(), state.getRowId(), outcome);
    }

    /** The outcome an instance status stands for; null while it still runs or when it failed. */
    static ApprovalOutcomeType outcomeOf(FlowExecutionStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case COMPLETED -> ApprovalOutcomeType.APPROVED;
            case REJECTED -> ApprovalOutcomeType.REJECTED;
            case RETURNED -> ApprovalOutcomeType.RETURNED;
            case WITHDRAWN -> ApprovalOutcomeType.WITHDRAWN;
            case CANCELLED -> ApprovalOutcomeType.CANCELLED;
            case PENDING, RUNNING, WAITING, FAILED -> null;
        };
    }

    /**
     * Whether the action behind this save left the status as it found it — a comment or a CC read
     * on an instance that already had its result. Told by the latest audit entry, the one the
     * action recorded.
     */
    private static boolean alreadyInThisStatus(FlowExecutionState state) {
        List<ApprovalActionAuditEntry> delta = state.getApprovalAuditDelta();
        return delta != null && !delta.isEmpty() && state.getStatus().equals(delta.getLast().getStatusBefore());
    }

    /** The latest action that produced {@code type}: who decided, and what they said. */
    private ApprovalActionAuditEntry decidingAction(FlowExecutionState state, ApprovalOutcomeType type) {
        Set<ApprovalActionType> deciding = switch (type) {
            case APPROVED -> Set.of(ApprovalActionType.APPROVE, ApprovalActionType.AUTO_APPROVE);
            case REJECTED -> Set.of(ApprovalActionType.REJECT);
            case RETURNED -> Set.of(ApprovalActionType.RETURN);
            case WITHDRAWN -> Set.of(ApprovalActionType.WITHDRAW);
            case CANCELLED -> Set.of(ApprovalActionType.TERMINATE);
        };
        if (deciding.isEmpty()) {
            return null;
        }
        // This action's own entries first; the committed history only when it recorded none
        // (an async step that resumed after the last approval).
        ApprovalActionAuditEntry latest = latestOf(state.getApprovalAuditDelta(), deciding);
        return latest != null ? latest : latestOf(auditReader.fullHistory(state), deciding);
    }

    private static ApprovalActionAuditEntry latestOf(List<ApprovalActionAuditEntry> entries,
                                                     Set<ApprovalActionType> actions) {
        if (entries == null) {
            return null;
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (actions.contains(entries.get(i).getAction())) {
                return entries.get(i);
            }
        }
        return null;
    }
}
