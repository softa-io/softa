package io.softa.starter.flow.runtime;

import java.util.List;

import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;

/**
 * Test-only ledger with no persistence: the state's delta buffer is never flushed, so
 * {@code ApprovalAuditReader.fullHistory} degrades to exactly the in-memory list — the
 * pre-ledger semantics Spring-free engine tests were written against.
 */
public class NoopApprovalActionLedger implements ApprovalActionLedger {

    @Override
    public void appendNewEntries(FlowExecutionState state) {
        // intentionally empty
    }

    @Override
    public List<ApprovalActionAuditEntry> findByInstanceId(String instanceId) {
        return List.of();
    }
}
