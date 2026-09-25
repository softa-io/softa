package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowApprovalRecord;
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
 */
@Service
public class FlowApprovalRecordServiceImpl extends EntityServiceImpl<FlowApprovalRecord, Long>
        implements ApprovalActionLedger {

    /**
     * Engine bookkeeping write — exempt from the caller's row scope; see
     * {@code FlowInstanceServiceImpl.saveInstance} for why the post-write scope re-read cannot
     * succeed on a {@code Flow*} ledger table. On the proxy-visible method, since the
     * {@code this.createList} below is a self-invocation the aspect would not see.
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @SkipPermissionCheck
    public void appendNewEntries(FlowExecutionState state) {
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
        // sequences continue from the instance's existing row count (resolved once, lazily).
        int base = state.getAuditSequenceBase();
        if (base < 0) {
            base = (int) this.count(new Filters().eq(FlowApprovalRecord::getInstanceId, state.getInstanceId()));
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
    }

    @Override
    public List<ApprovalActionAuditEntry> findByInstanceId(String instanceId) {
        return this.searchList(new Filters().eq(FlowApprovalRecord::getInstanceId, instanceId)).stream()
                .sorted(Comparator.comparing(FlowApprovalRecord::getSequence,
                        Comparator.nullsLast(Integer::compareTo)))
                .map(FlowApprovalRecordServiceImpl::toEntry)
                .toList();
    }

    /** Entry → row mapping; {@code sequence} is assigned by the flush loop, not copied. */
    static FlowApprovalRecord toRecord(FlowExecutionState state, ApprovalActionAuditEntry entry) {
        FlowApprovalRecord record = new FlowApprovalRecord();
        record.setInstanceId(state.getInstanceId());
        record.setFlowCode(entry.getFlowCode() == null ? state.getFlowCode() : entry.getFlowCode());
        record.setFlowRevision(entry.getFlowRevision() == null ? state.getFlowRevision() : entry.getFlowRevision());
        record.setNodeId(entry.getNodeId());
        record.setNodeLabel(entry.getNodeLabel());
        record.setCycleNumber(entry.getCycleNumber());
        record.setAction(entry.getAction());
        record.setActorId(entry.getActorId());
        record.setTargetActorId(entry.getTargetActorId());
        record.setAddSignPosition(entry.getAddSignPosition());
        record.setTargetNodeId(entry.getTargetNodeId());
        record.setTargetNodeLabel(entry.getTargetNodeLabel());
        record.setComment(entry.getComment());
        record.setStatusBefore(entry.getStatusBefore());
        record.setStatusAfter(entry.getStatusAfter());
        record.setApprovedActors(entry.getApprovedActors());
        record.setRejectedActors(entry.getRejectedActors());
        record.setVariableKeys(entry.getVariableKeys());
        record.setEventTime(entry.getEventTime());
        return record;
    }

    /** Row → entry mapping for ledger readers (threshold snapshots are not persisted by design). */
    static ApprovalActionAuditEntry toEntry(FlowApprovalRecord record) {
        return ApprovalActionAuditEntry.builder()
                .sequence(record.getSequence())
                .action(record.getAction())
                .eventTime(record.getEventTime())
                .flowCode(record.getFlowCode())
                .flowRevision(record.getFlowRevision())
                .nodeId(record.getNodeId())
                .nodeLabel(record.getNodeLabel())
                .cycleNumber(record.getCycleNumber())
                .actorId(record.getActorId())
                .targetActorId(record.getTargetActorId())
                .addSignPosition(record.getAddSignPosition())
                .targetNodeId(record.getTargetNodeId())
                .targetNodeLabel(record.getTargetNodeLabel())
                .comment(record.getComment())
                .statusBefore(record.getStatusBefore())
                .statusAfter(record.getStatusAfter())
                .approvedActors(record.getApprovedActors())
                .rejectedActors(record.getRejectedActors())
                .variableKeys(record.getVariableKeys())
                .build();
    }
}
