package io.softa.starter.flow.service.support.view;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowDelegationView;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.enums.FlowDelegationStatus;
import io.softa.starter.flow.service.support.FlowUserNames;

/**
 * Delegation rules as their lists show them: the delegator and the delegate named, one lookup for the whole list, and
 * each rule's status now — the one the delegation lookup routes by.
 */
@Component
public class FlowDelegationViews {

    private final FlowUserNames names;

    public FlowDelegationViews(FlowUserNames names) {
        this.names = names;
    }

    public List<FlowDelegationView> of(List<FlowDelegation> delegations) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, String> byUser = names.namesOf(delegations.stream()
                .flatMap(delegation -> Stream.of(delegation.getDelegatorId(), delegation.getDelegateId()))
                .filter(Objects::nonNull)
                .distinct()
                .toList());
        return delegations.stream().map(delegation -> FlowDelegationView.builder()
                .id(delegation.getId())
                .delegatorId(delegation.getDelegatorId())
                .delegatorName(byUser.get(delegation.getDelegatorId()))
                .delegateId(delegation.getDelegateId())
                .delegateName(byUser.get(delegation.getDelegateId()))
                .reason(delegation.getReason())
                .scope(delegation.getScope())
                .flowId(delegation.getFlowId())
                .flowName(delegation.getFlowName())
                .nodeId(delegation.getNodeId())
                .startTime(delegation.getStartTime())
                .endTime(delegation.getEndTime())
                .status(FlowDelegationStatus.of(delegation, now))
                .delegatedTaskCount(delegation.getDelegatedTaskCount())
                .lastDelegationTime(delegation.getLastDelegationTime())
                .createdTime(delegation.getCreatedTime())
                .createdBy(delegation.getCreatedBy())
                .build()).toList();
    }

    /** A page of rules as {@link #of} shows them, numbered as it was read. */
    public Page<FlowDelegationView> of(Page<FlowDelegation> delegations) {
        Page<FlowDelegationView> page = Page.of(delegations.getPageNumber(), delegations.getPageSize());
        page.setTotalCount(delegations.getTotalCount());
        page.setRows(of(delegations.getRows()));
        return page;
    }
}
