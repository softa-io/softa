package io.softa.starter.flow.service.impl;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.dto.FlowApprovalActionView;
import io.softa.starter.flow.entity.FlowApprovalAction;
import io.softa.starter.flow.runtime.engine.FlowParticipants;
import io.softa.starter.flow.service.FlowApprovalActionQueryService;
import io.softa.starter.flow.service.query.ApprovalActionQuerySupport;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;
import io.softa.starter.flow.service.support.view.FlowApprovalActionViewMapper;

/**
 * Authorized cross-actor query API over {@code flow_approval_action}, backed by
 * {@link FlowApprovalActionServiceImpl} for row access and {@link FlowInstanceAccessGuard}
 * for participant scoping.
 * <p>
 * Kept separate from {@link FlowApprovalActionServiceImpl} (the ledger implementation)
 * because the guard's participant check reads the runtime's full audit history, which in
 * turn reads the ledger — folding this query API into the ledger bean would close that into
 * a circular dependency.
 */
@Service
public class FlowApprovalActionQueryServiceImpl implements FlowApprovalActionQueryService {

    private final FlowApprovalActionServiceImpl recordService;

    private final FlowInstanceAccessGuard accessGuard;

    private final FlowUserNames names;

    public FlowApprovalActionQueryServiceImpl(FlowApprovalActionServiceImpl recordService,
                                               FlowInstanceAccessGuard accessGuard, FlowUserNames names) {
        this.recordService = recordService;
        this.accessGuard = accessGuard;
        this.names = names;
    }

    @Override
    public List<FlowApprovalActionView> getByInstanceId(String instanceId, String requesterId) {
        List<FlowApprovalAction> records = getRecordEntitiesByInstanceId(instanceId);
        // Whoever acted — but for an administrator's urge or termination — or was acted toward takes part.
        boolean participant = records.stream().anyMatch(record -> requesterId != null
                && ((FlowParticipants.actorTakesPart(record.getAction()) && requesterId.equals(record.getActorId()))
                || requesterId.equals(record.getTargetActorId())));
        accessGuard.requireInstanceViewer(instanceId, requesterId, participant);
        List<FlowApprovalActionView> views = FlowApprovalActionViewMapper.toViews(records);
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

    /** Every record of an instance, past the caller's data permissions — the access guard rules on them. */
    private List<FlowApprovalAction> getRecordEntitiesByInstanceId(String instanceId) {
        return ContextUtils.withoutPermissionChecks(
                () -> recordService.searchList(new Filters().eq(FlowApprovalAction::getInstanceId, instanceId))).stream()
                .sorted(historyComparator())
                .toList();
    }

    private static Comparator<FlowApprovalAction> historyComparator() {
        return ApprovalActionQuerySupport.historyComparator();
    }
}
