package io.softa.starter.flow.service.support.view;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.dto.FlowDelegationView;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.service.support.FlowUserNames;

/**
 * Delegation rules as their lists show them: the delegator and the delegate named, one lookup for the whole list.
 */
@Component
public class FlowDelegationViews {

    private final FlowUserNames names;

    public FlowDelegationViews(FlowUserNames names) {
        this.names = names;
    }

    public List<FlowDelegationView> of(List<FlowDelegation> delegations) {
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
                .active(delegation.getActive())
                .autoExpire(delegation.getAutoExpire())
                .delegatedTaskCount(delegation.getDelegatedTaskCount())
                .lastDelegationTime(delegation.getLastDelegationTime())
                .createdTime(delegation.getCreatedTime())
                .build()).toList();
    }
}
