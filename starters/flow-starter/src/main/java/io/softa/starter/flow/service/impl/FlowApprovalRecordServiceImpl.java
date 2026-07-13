package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.dto.FlowApprovalRecordView;
import io.softa.starter.flow.dto.FlowSentCcView;
import io.softa.starter.flow.entity.FlowApprovalRecord;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;
import io.softa.starter.flow.service.FlowApprovalRecordQueryService;
import io.softa.starter.flow.service.query.ApprovalRecordQuerySupport;
import io.softa.starter.flow.service.query.SentCcHistoryComposer;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.view.FlowApprovalRecordViewMapper;

/**
 * ORM-backed approval record query service, doubling as the {@link ApprovalActionLedger}:
 * the instance store flushes each attempt's audit delta here, making
 * {@code flow_approval_record} the single authority for approval action history.
 */
@Slf4j
@Service
public class FlowApprovalRecordServiceImpl extends EntityServiceImpl<FlowApprovalRecord, Long>
        implements FlowApprovalRecordQueryService, ApprovalActionLedger {

    private static final int DEFAULT_PAGE_SIZE = 50;

    /** Safety cap on the sent-CC scan (its OR + read-correlation cannot be cleanly DB-paginated). */
    private static final int MAX_SENT_CC_ROWS = 500;

    private final FlowInstanceAccessGuard accessGuard;

    public FlowApprovalRecordServiceImpl(FlowInstanceAccessGuard accessGuard) {
        this.accessGuard = accessGuard;
    }

    @Override
    public List<FlowApprovalRecordView> getByInstanceId(String instanceId, String requesterId) {
        List<FlowApprovalRecord> records = getRecordEntitiesByInstanceId(instanceId);
        boolean participant = records.stream().anyMatch(record -> requesterId != null
                && (requesterId.equals(record.getActorId()) || requesterId.equals(record.getTargetActorId())));
        accessGuard.requireInstanceViewer(instanceId, requesterId, participant);
        return FlowApprovalRecordViewMapper.toViews(records);
    }

    @Override
    public Page<FlowApprovalRecordView> getHistory(String actorId, String flowCode, String instanceId, String nodeId,
                                                   Integer pageNumber, Integer pageSize) {
        ApprovalRecordQuerySupport.requireActorId(actorId);
        Filters filters = new Filters().eq(FlowApprovalRecord::getActorId, actorId);
        if (StringUtils.hasText(flowCode)) {
            filters.eq(FlowApprovalRecord::getFlowCode, flowCode);
        }
        if (StringUtils.hasText(instanceId)) {
            filters.eq(FlowApprovalRecord::getInstanceId, instanceId);
        }
        if (StringUtils.hasText(nodeId)) {
            filters.eq(FlowApprovalRecord::getNodeId, nodeId);
        }
        // Filters + newest-first ordering are pushed to the query, so only one page is materialized.
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowApprovalRecord::getEventTime)
                .addDesc(FlowApprovalRecord::getSequence)
                .addDesc(FlowApprovalRecord::getId));
        int pn = pageNumber != null ? pageNumber : 1;
        int ps = pageSize != null ? pageSize : DEFAULT_PAGE_SIZE;
        Page<FlowApprovalRecord> source = this.searchPage(query, Page.of(pn, ps));

        Page<FlowApprovalRecordView> target = Page.of(pn, ps);
        target.setTotalCount(source.getTotalCount());
        target.setRows(FlowApprovalRecordViewMapper.toViews(source.getRows()));
        return target;
    }

    @Override
    public List<FlowSentCcView> getSentCcHistory(String actorId, Boolean read, String flowCode, String instanceId, String nodeId) {
        ApprovalRecordQuerySupport.requireActorId(actorId);
        List<FlowApprovalRecord> records = collectSentCcQueryRecords(actorId, instanceId);
        return composeSentCcHistory(records, actorId, read, flowCode, instanceId, nodeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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

    static List<FlowSentCcView> composeSentCcHistory(List<FlowApprovalRecord> records,
                                                            String actorId,
                                                            Boolean read,
                                                            String flowCode,
                                                            String instanceId,
                                                            String nodeId) {
        return SentCcHistoryComposer.compose(records, actorId, read, flowCode, instanceId, nodeId);
    }

    private List<FlowApprovalRecord> getRecordEntitiesByInstanceId(String instanceId) {
        return this.searchList(new Filters().eq(FlowApprovalRecord::getInstanceId, instanceId)).stream()
                .sorted(historyComparator())
                .toList();
    }

    private List<FlowApprovalRecord> collectSentCcQueryRecords(String actorId, String instanceId) {
        if (StringUtils.hasText(instanceId)) {
            return getRecordEntitiesByInstanceId(instanceId);
        }
        Map<String, FlowApprovalRecord> deduplicated = new LinkedHashMap<>();
        boundedRecentByActor(new Filters().eq(FlowApprovalRecord::getActorId, actorId), actorId)
                .forEach(record -> deduplicated.putIfAbsent(recordKey(record), record));
        boundedRecentByActor(new Filters().eq(FlowApprovalRecord::getTargetActorId, actorId), actorId)
                .forEach(record -> deduplicated.putIfAbsent(recordKey(record), record));
        return deduplicated.values().stream().toList();
    }

    /** Newest-first, capped scan for the sent-CC query so a heavy actor's records aren't fully loaded. */
    private List<FlowApprovalRecord> boundedRecentByActor(Filters filters, String actorId) {
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowApprovalRecord::getEventTime));
        query.setLimitSize(MAX_SENT_CC_ROWS);
        List<FlowApprovalRecord> rows = this.searchList(query);
        if (rows.size() >= MAX_SENT_CC_ROWS) {
            log.warn("Sent-CC history for actor {} hit the {}-row scan cap; older entries are omitted",
                    actorId, MAX_SENT_CC_ROWS);
        }
        return rows;
    }

    private static String recordKey(FlowApprovalRecord record) {
        if (record.getId() != null) {
            return "id:" + record.getId();
        }
        if (record.getSequence() != null) {
            return "instance:" + record.getInstanceId() + ":seq:" + record.getSequence();
        }
        return "fallback:"
                + record.getInstanceId() + ':'
                + record.getNodeId() + ':'
                + record.getCycleNumber() + ':'
                + record.getActorId() + ':'
                + record.getTargetActorId() + ':'
                + record.getAction() + ':'
                + record.getEventTime();
    }

    private static Comparator<FlowApprovalRecord> historyComparator() {
        return ApprovalRecordQuerySupport.historyComparator();
    }

}

