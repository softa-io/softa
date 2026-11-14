package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowApprovalRecord;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;

/**
 * ORM-backed {@link ApprovalActionLedger}: the instance store flushes each attempt's audit
 * delta here, making {@code flow_approval_record} the single authority for approval action
 * history.
 * <p>
 * Deliberately excludes the authorized cross-actor query API (see
 * {@link FlowApprovalRecordQueryServiceImpl} instead): the runtime's audit reader depends on
 * this ledger, and the instance access guard depends on that reader, so a query method here
 * needing the guard would close a dependency cycle back onto this very bean.
 * <p>
 * Both port methods run past the caller's data permissions: the ledger is the engine's bookkeeping,
 * read by the actor checks and the access guard before either has decided anything about the caller.
 */
@Service
public class FlowApprovalRecordServiceImpl extends EntityServiceImpl<FlowApprovalRecord, Long>
        implements ApprovalActionLedger {

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void appendNewEntries(FlowExecutionState state) {
        ContextUtils.withoutPermissionChecks(() -> flush(state));
    }

    private void flush(FlowExecutionState state) {
        if (state == null || state.getInstanceId() == null) {
            return;
        }
        List<ApprovalActionAuditEntry> entries = state.getApprovalAuditDelta();
        if (entries == null || entries.isEmpty()) {
            return;
        }
        int already = Math.max(0, state.getPersistedAuditCount());
        if (already >= entries.size()) {
            return;
        }
        // The in-memory audit list is a delta buffer: loaded states start it empty, so persisted
        // sequences continue from the instance's existing rows — as the action read them, else read
        // now (resolved once, lazily): the task projection after the save reads them anyway.
        int base = state.getAuditSequenceBase();
        if (base < 0) {
            base = ApprovalAuditReader.committed(state, this).size();
            state.setAuditSequenceBase(base);
        }
        List<FlowApprovalRecord> rows = new ArrayList<>(entries.size() - already);
        for (int i = already; i < entries.size(); i++) {
            FlowApprovalRecord row = toRecord(state, entries.get(i));
            row.setSequence(base + i);
            rows.add(row);
        }
        this.createList(rows);
        state.setPersistedAuditCount(entries.size());
        // What was just written joins the rows the action read, as a read would return them.
        if (state.getCommittedAudit() != null) {
            rows.forEach(row -> state.getCommittedAudit().add(toEntry(row)));
        }
    }

    @Override
    public List<ApprovalActionAuditEntry> findByInstanceId(String instanceId) {
        return ContextUtils.withoutPermissionChecks(
                () -> this.searchList(new Filters().eq(FlowApprovalRecord::getInstanceId, instanceId))).stream()
                .sorted(Comparator.comparing(FlowApprovalRecord::getSequence,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(FlowApprovalRecordServiceImpl::toEntry)
                .toList();
    }

    /** Entry → row mapping; {@code sequence} is assigned by the flush loop, not copied. */
    static FlowApprovalRecord toRecord(FlowExecutionState state, ApprovalActionAuditEntry entry) {
        FlowApprovalRecord record = new FlowApprovalRecord();
        record.setInstanceId(state.getInstanceId());
        record.setFlowId(entry.getFlowId() == null ? state.getFlowId() : entry.getFlowId());
        record.setFlowRevision(entry.getFlowRevision() == null ? state.getFlowRevision() : entry.getFlowRevision());
        record.setNodeId(entry.getNodeId());
        record.setNodeLabel(entry.getNodeLabel());
        record.setAction(entry.getAction());
        record.setActorId(entry.getActorId());
        record.setTargetActorId(entry.getTargetActorId());
        record.setOperatorId(entry.getOperatorId());
        record.setComment(entry.getComment());
        record.setStatusBefore(entry.getStatusBefore());
        record.setStatusAfter(entry.getStatusAfter());
        record.setApprovedActors(entry.getApprovedActors());
        record.setEventTime(entry.getEventTime());
        return record;
    }

    /** Row → entry mapping for ledger readers (threshold snapshots are not persisted by design). */
    static ApprovalActionAuditEntry toEntry(FlowApprovalRecord record) {
        return ApprovalActionAuditEntry.builder()
                .sequence(record.getSequence())
                .action(record.getAction())
                .eventTime(record.getEventTime())
                .flowId(record.getFlowId())
                .flowRevision(record.getFlowRevision())
                .nodeId(record.getNodeId())
                .nodeLabel(record.getNodeLabel())
                .actorId(record.getActorId())
                .targetActorId(record.getTargetActorId())
                .operatorId(record.getOperatorId())
                .comment(record.getComment())
                .statusBefore(record.getStatusBefore())
                .statusAfter(record.getStatusAfter())
                .approvedActors(record.getApprovedActors())
                .build();
    }
}
