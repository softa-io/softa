package io.softa.starter.flow.runtime.store;

import java.util.List;

import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Persistence port for the approval action audit ledger ({@code flow_approval_record}) —
 * the single authority for approval lifecycle history.
 * <p>
 * The runtime keeps {@link FlowExecutionState#getApprovalAuditDelta()} as a delta buffer
 * of the current attempt; the instance store flushes it here on every save, in the same
 * transaction as the instance row (a failed attempt's REQUIRES_NEW failure record therefore
 * carries its audit rows too). Readers needing full history combine {@link #findByInstanceId}
 * with the state's unflushed tail.
 */
public interface ApprovalActionLedger {

    /**
     * Persist the audit entries accumulated on the state since the last flush. Sequence
     * numbers continue from the instance's existing row count (resolved lazily with one
     * COUNT for loaded states); after a successful flush the state's watermark advances.
     */
    void appendNewEntries(FlowExecutionState state);

    /**
     * Committed audit rows for an instance, ordered by sequence ascending, mapped back
     * to audit entries.
     */
    List<ApprovalActionAuditEntry> findByInstanceId(String instanceId);
}
