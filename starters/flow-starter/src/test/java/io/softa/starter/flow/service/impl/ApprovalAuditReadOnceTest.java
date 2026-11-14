package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.entity.FlowApprovalRecord;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * An action reads an instance's approval history once: the committed rows are kept on its state, and what the action
 * flushes joins them there — the next step's approvers, the replay check and the task projection read no more, and
 * the flush needs no COUNT to number its rows.
 */
class ApprovalAuditReadOnceTest {

    @Test
    void aLoadedInstanceReadsItsHistoryOnce() {
        AtomicInteger reads = new AtomicInteger();
        ApprovalAuditReader reader = new ApprovalAuditReader(ledger(reads, List.of(entry(ApprovalActionType.CC, 0))));
        FlowExecutionState state = loaded();

        reader.fullHistory(state);
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.APPROVE, null));
        List<ApprovalActionAuditEntry> history = reader.fullHistory(state);

        assertEquals(1, reads.get());
        assertEquals(List.of(ApprovalActionType.CC, ApprovalActionType.APPROVE),
                history.stream().map(ApprovalActionAuditEntry::getAction).toList());
    }

    @Test
    void aNewInstanceReadsNothing() {
        AtomicInteger reads = new AtomicInteger();
        ApprovalAuditReader reader = new ApprovalAuditReader(ledger(reads, List.of()));
        FlowExecutionState state = FlowExecutionState.builder().instanceId("i1")
                .approvalAuditDelta(new ArrayList<>()).committedAudit(new ArrayList<>()).build();

        reader.fullHistory(state);

        assertEquals(0, reads.get());
    }

    @Test
    void whatTheActionFlushesJoinsTheRowsItReadWithoutCountingThem() {
        FlowApprovalRecordServiceImpl ledger = spy(new FlowApprovalRecordServiceImpl());
        FlowApprovalRecord submitted = FlowApprovalRecordServiceImpl.toRecord(loaded(), entry(ApprovalActionType.CC, null));
        submitted.setSequence(0);
        doReturn(List.of(submitted)).when(ledger).searchList(any(Filters.class));
        doReturn(List.of()).when(ledger).createList(anyList());
        ApprovalAuditReader reader = new ApprovalAuditReader(ledger);
        FlowExecutionState state = loaded();
        reader.fullHistory(state);
        state.getApprovalAuditDelta().add(entry(ApprovalActionType.APPROVE, null));

        ledger.appendNewEntries(state);
        List<ApprovalActionAuditEntry> history = reader.fullHistory(state);

        verify(ledger, times(1)).searchList(any(Filters.class));
        verify(ledger, never()).count(any(Filters.class));
        assertEquals(1, state.getAuditSequenceBase(), "the flushed row is numbered after the one read");
        assertEquals(List.of(0, 1), history.stream().map(ApprovalActionAuditEntry::getSequence).toList());
    }

    /** A state loaded from the store: its rows not read yet, their count unknown. */
    private static FlowExecutionState loaded() {
        return FlowExecutionState.builder().instanceId("i1").flowId("leave").flowRevision(1)
                .approvalAuditDelta(new ArrayList<>()).auditSequenceBase(-1).build();
    }

    private static ApprovalActionAuditEntry entry(ApprovalActionType action, Integer sequence) {
        return ApprovalActionAuditEntry.builder().action(action).nodeId("manager").sequence(sequence).build();
    }

    private static ApprovalActionLedger ledger(AtomicInteger reads, List<ApprovalActionAuditEntry> rows) {
        return new ApprovalActionLedger() {
            @Override
            public void appendNewEntries(FlowExecutionState state) {
            }

            @Override
            public List<ApprovalActionAuditEntry> findByInstanceId(String instanceId) {
                reads.incrementAndGet();
                return rows;
            }
        };
    }
}
