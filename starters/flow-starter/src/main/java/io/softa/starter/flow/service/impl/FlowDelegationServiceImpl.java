package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.service.FlowDelegationService;

/**
 * ORM-backed delegation service.
 */
@Service
public class FlowDelegationServiceImpl extends EntityServiceImpl<FlowDelegation, Long>
        implements FlowDelegationService {

    @Override
    public FlowDelegation createDelegation(FlowDelegation delegation) {
        Long id = this.createOne(delegation);
        delegation.setId(id);
        return delegation;
    }

    @Override
    public List<FlowDelegation> findActiveDelegations(String delegatorId, LocalDateTime now) {
        Filters filters = new Filters()
                .eq(FlowDelegation::getDelegatorId, delegatorId)
                .eq(FlowDelegation::getActive, true);
        return this.searchList(filters).stream()
                .filter(d -> isWithinDateRange(d, now))
                .toList();
    }

    @Override
    public Optional<FlowDelegation> findActiveDelegation(String delegatorId, String flowCode, String nodeId, LocalDateTime now) {
        List<FlowDelegation> active = findActiveDelegations(delegatorId, now);
        // Prefer most specific scope: node > flow > all
        return active.stream()
                .filter(d -> matchesScope(d, flowCode, nodeId))
                .max(Comparator.comparingInt(d -> scopePriority(d, flowCode, nodeId)));
    }

    @Override
    public int expireDelegations(LocalDateTime now) {
        Filters filters = new Filters()
                .eq(FlowDelegation::getActive, true)
                .eq(FlowDelegation::getAutoExpire, true);
        List<FlowDelegation> candidates = this.searchList(filters);
        int count = 0;
        for (FlowDelegation d : candidates) {
            if (d.getEndTime() != null && d.getEndTime().isBefore(now)) {
                d.setActive(false);
                this.updateOne(d, false);
                count++;
            }
        }
        return count;
    }

    @Override
    public void cancelDelegation(Long delegationId) {
        this.getById(delegationId).ifPresent(d -> {
            d.setActive(false);
            this.updateOne(d, false);
        });
    }

    @Override
    public List<FlowDelegation> getMyDelegations(String delegatorId) {
        Filters filters = new Filters().eq(FlowDelegation::getDelegatorId, delegatorId);
        return this.searchList(filters);
    }

    @Override
    public List<FlowDelegation> getDelegationsToMe(String delegateId) {
        Filters filters = new Filters()
                .eq(FlowDelegation::getDelegateId, delegateId)
                .eq(FlowDelegation::getActive, true);
        return this.searchList(filters);
    }

    @Override
    public void recordDelegationUsage(Long delegationId) {
        this.getById(delegationId).ifPresent(d -> {
            d.setDelegatedTaskCount((d.getDelegatedTaskCount() != null ? d.getDelegatedTaskCount() : 0) + 1);
            d.setLastDelegationTime(LocalDateTime.now());
            this.updateOne(d, false);
        });
    }

    // --- private helpers ---

    private boolean isWithinDateRange(FlowDelegation d, LocalDateTime now) {
        if (d.getStartTime() != null && now.isBefore(d.getStartTime())) {
            return false;
        }
        return d.getEndTime() == null || !now.isAfter(d.getEndTime());
    }

    private boolean matchesScope(FlowDelegation d, String flowCode, String nodeId) {
        String scope = d.getScope();
        if (scope == null || "ALL".equalsIgnoreCase(scope)) {
            return true;
        }
        if ("FLOW".equalsIgnoreCase(scope)) {
            return Objects.equals(d.getFlowCode(), flowCode);
        }
        if ("NODE".equalsIgnoreCase(scope)) {
            return Objects.equals(d.getFlowCode(), flowCode) && Objects.equals(d.getNodeId(), nodeId);
        }
        return false;
    }

    private int scopePriority(FlowDelegation d, String flowCode, String nodeId) {
        String scope = d.getScope();
        if ("NODE".equalsIgnoreCase(scope) && Objects.equals(d.getFlowCode(), flowCode) && Objects.equals(d.getNodeId(), nodeId)) {
            return 3;
        }
        if ("FLOW".equalsIgnoreCase(scope) && Objects.equals(d.getFlowCode(), flowCode)) {
            return 2;
        }
        if (scope == null || "ALL".equalsIgnoreCase(scope)) {
            return 1;
        }
        return 0;
    }
}

