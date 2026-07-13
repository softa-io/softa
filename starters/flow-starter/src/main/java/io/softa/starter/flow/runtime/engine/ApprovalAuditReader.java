package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;

/**
 * Single read path for an instance's full approval action history: the committed ledger
 * rows plus the state's unflushed delta tail. Mid-attempt readers (approver dedup, cycle
 * numbering after a same-transaction resubmit) see entries appended earlier in the same
 * attempt; request-time readers see exactly the committed rows.
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
        List<ApprovalActionAuditEntry> committed = state.getInstanceId() == null
                ? List.of()
                : ledger.findByInstanceId(state.getInstanceId());
        List<ApprovalActionAuditEntry> delta = state.getApprovalAuditDelta();
        int watermark = Math.max(0, state.getPersistedAuditCount());
        if (delta == null || delta.size() <= watermark) {
            return committed;
        }
        List<ApprovalActionAuditEntry> merged = new ArrayList<>(committed.size() + delta.size() - watermark);
        merged.addAll(committed);
        merged.addAll(delta.subList(watermark, delta.size()));
        return merged;
    }
}
