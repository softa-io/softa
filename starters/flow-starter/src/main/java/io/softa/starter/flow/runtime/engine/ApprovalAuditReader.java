package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;

/**
 * Single read path for an instance's full approval action history: the committed ledger
 * rows plus the state's unflushed delta tail. Mid-attempt readers (approver dedup, the
 * replay guard) see entries appended earlier in the same attempt; request-time readers see
 * exactly the committed rows.
 *
 * <p>The committed rows are read once per state and kept on it ({@code committedAudit}); the ledger adds what it
 * flushes there, so the rest of the action — the next step's approvers, the task projection — reads no more.
 */
@Component
public class ApprovalAuditReader {

    private final ApprovalActionLedger ledger;

    public ApprovalAuditReader(ApprovalActionLedger ledger) {
        this.ledger = ledger;
    }

    /** Committed rows (sequence order) followed by the unflushed in-memory tail. Never null. */
    public List<ApprovalActionAuditEntry> fullHistory(FlowExecutionState state) {
        if (state == null) {
            return List.of();
        }
        List<ApprovalActionAuditEntry> committed = committed(state, ledger);
        List<ApprovalActionAuditEntry> delta = state.getApprovalAuditDelta();
        int watermark = Math.max(0, state.getPersistedAuditCount());
        if (delta == null || delta.size() <= watermark) {
            return Collections.unmodifiableList(committed);
        }
        List<ApprovalActionAuditEntry> merged = new ArrayList<>(committed.size() + delta.size() - watermark);
        merged.addAll(committed);
        merged.addAll(delta.subList(watermark, delta.size()));
        return merged;
    }

    /** The instance's committed rows, read from {@code ledger} the first time this state asks and kept on it. */
    public static List<ApprovalActionAuditEntry> committed(FlowExecutionState state, ApprovalActionLedger ledger) {
        if (state.getCommittedAudit() == null) {
            state.setCommittedAudit(state.getInstanceId() == null
                    ? new ArrayList<>() : new ArrayList<>(ledger.findByInstanceId(state.getInstanceId())));
        }
        return state.getCommittedAudit();
    }
}
