package io.softa.starter.flow.service.impl;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowApprovalRecordView;
import io.softa.starter.flow.dto.FlowSentCcView;
import io.softa.starter.flow.entity.FlowApprovalRecord;
import io.softa.starter.flow.service.FlowApprovalRecordQueryService;
import io.softa.starter.flow.service.query.ApprovalRecordQuerySupport;
import io.softa.starter.flow.service.query.SentCcHistoryComposer;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;
import io.softa.starter.flow.service.support.view.FlowApprovalRecordViewMapper;

/**
 * Authorized cross-actor query API over {@code flow_approval_record}, backed by
 * {@link FlowApprovalRecordServiceImpl} for row access and {@link FlowInstanceAccessGuard}
 * for participant scoping.
 * <p>
 * Kept separate from {@link FlowApprovalRecordServiceImpl} (the ledger implementation)
 * because the guard's participant check reads the runtime's full audit history, which in
 * turn reads the ledger — folding this query API into the ledger bean would close that into
 * a circular dependency.
 */
@Slf4j
@Service
public class FlowApprovalRecordQueryServiceImpl implements FlowApprovalRecordQueryService {

    private static final int DEFAULT_PAGE_SIZE = 50;

    /** Safety cap on the sent-CC scan (its OR + read-correlation cannot be cleanly DB-paginated). */
    private static final int MAX_SENT_CC_ROWS = 500;

    private final FlowApprovalRecordServiceImpl recordService;

    private final FlowInstanceAccessGuard accessGuard;

    private final FlowUserNames names;

    public FlowApprovalRecordQueryServiceImpl(FlowApprovalRecordServiceImpl recordService,
                                               FlowInstanceAccessGuard accessGuard, FlowUserNames names) {
        this.recordService = recordService;
        this.accessGuard = accessGuard;
        this.names = names;
    }

    @Override
    public List<FlowApprovalRecordView> getByInstanceId(String instanceId, String requesterId) {
        List<FlowApprovalRecord> records = getRecordEntitiesByInstanceId(instanceId);
        boolean participant = records.stream().anyMatch(record -> requesterId != null
                && (requesterId.equals(record.getActorId()) || requesterId.equals(record.getTargetActorId())));
        accessGuard.requireInstanceViewer(instanceId, requesterId, participant);
        List<FlowApprovalRecordView> views = FlowApprovalRecordViewMapper.toViews(records);
        // The instance's history, read by its participants: whoever acted and whom they acted toward, named.
        Map<String, String> byUser = names.namesOf(views.stream()
                .flatMap(view -> Stream.of(view.getActorId(), view.getTargetActorId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        views.forEach(view -> {
            view.setActorName(byUser.get(view.getActorId()));
            view.setTargetActorName(byUser.get(view.getTargetActorId()));
        });
        return views;
    }

    @Override
    public Page<FlowApprovalRecordView> getHistory(String actorId, String flowId, String instanceId, String nodeId,
                                                   Integer pageNumber, Integer pageSize) {
        ApprovalRecordQuerySupport.requireActorId(actorId);
        Filters filters = new Filters().eq(FlowApprovalRecord::getActorId, actorId);
        if (StringUtils.hasText(flowId)) {
            filters.eq(FlowApprovalRecord::getFlowId, flowId);
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
        Page<FlowApprovalRecord> source = recordService.searchPage(query, Page.of(pn, ps));

        Page<FlowApprovalRecordView> target = Page.of(pn, ps);
        target.setTotalCount(source.getTotalCount());
        target.setRows(FlowApprovalRecordViewMapper.toViews(source.getRows()));
        return target;
    }

    @Override
    public List<FlowSentCcView> getSentCcHistory(String actorId, Boolean read, String flowId, String instanceId, String nodeId) {
        ApprovalRecordQuerySupport.requireActorId(actorId);
        List<FlowApprovalRecord> records = collectSentCcQueryRecords(actorId, instanceId);
        return composeSentCcHistory(records, actorId, read, flowId, instanceId, nodeId);
    }

    static List<FlowSentCcView> composeSentCcHistory(List<FlowApprovalRecord> records,
                                                            String actorId,
                                                            Boolean read,
                                                            String flowId,
                                                            String instanceId,
                                                            String nodeId) {
        return SentCcHistoryComposer.compose(records, actorId, read, flowId, instanceId, nodeId);
    }

    /** Every record of an instance, past the caller's data permissions — the access guard rules on them. */
    private List<FlowApprovalRecord> getRecordEntitiesByInstanceId(String instanceId) {
        return ContextUtils.withoutPermissionChecks(
                () -> recordService.searchList(new Filters().eq(FlowApprovalRecord::getInstanceId, instanceId))).stream()
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
        List<FlowApprovalRecord> rows = recordService.searchList(query);
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
