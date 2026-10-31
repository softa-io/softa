package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
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

    /** Active rules for one delegator at {@code now} — feeds the scope-aware single lookup below. */
    private List<FlowDelegation> findActiveDelegations(String delegatorId, LocalDateTime now) {
        Filters filters = new Filters()
                .eq(FlowDelegation::getDelegatorId, delegatorId)
                .eq(FlowDelegation::getActive, true);
        return this.searchList(filters).stream()
                .filter(d -> isWithinDateRange(d, now))
                .toList();
    }

    @Override
    public Optional<FlowDelegation> findActiveDelegation(String delegatorId, String flowId, String nodeId, LocalDateTime now) {
        List<FlowDelegation> active = findActiveDelegations(delegatorId, now);
        // Prefer most specific scope: node > flow > all
        return active.stream()
                .filter(d -> matchesScope(d, flowId, nodeId))
                .max(Comparator.comparingInt(d -> scopePriority(d, flowId, nodeId)));
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
                writeInactive(d);
                count++;
            }
        }
        return count;
    }

    @Override
    public void cancelDelegation(Long delegationId, String requesterId) {
        FlowDelegation delegation = this.getById(delegationId)
                .filter(d -> requesterId != null && requesterId.equals(d.getDelegatorId()))
                .orElseThrow(() -> new FlowAuthorizationException(
                        "You have no delegation " + delegationId + " to cancel"));
        writeInactive(delegation);
    }

    @Override
    public List<FlowDelegation> listAll() {
        return this.searchList(new FlexQuery(new Filters(), Orders.ofDesc(FlowDelegation::getCreatedTime)));
    }

    @Override
    public void deactivate(Long delegationId) {
        FlowDelegation delegation = this.getById(delegationId)
                .orElseThrow(() -> new FlowActionValidationException("No delegation " + delegationId + " to cancel"));
        writeInactive(delegation);
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
            FlowDelegation patch = patchOf(d);
            patch.setDelegatedTaskCount((d.getDelegatedTaskCount() != null ? d.getDelegatedTaskCount() : 0) + 1);
            patch.setLastDelegationTime(LocalDateTime.now());
            this.updateOne(patch);
        });
    }

    // --- private helpers ---

    /**
     * Written back whole, a delegation read with any field cut would clear that field — the delegate,
     * scope or dates. Each change writes only the id and the fields it sets.
     */
    private static FlowDelegation patchOf(FlowDelegation delegation) {
        FlowDelegation patch = new FlowDelegation();
        patch.setId(delegation.getId());
        return patch;
    }

    private void writeInactive(FlowDelegation delegation) {
        FlowDelegation patch = patchOf(delegation);
        patch.setActive(false);
        this.updateOne(patch);
    }

    private boolean isWithinDateRange(FlowDelegation d, LocalDateTime now) {
        if (d.getStartTime() != null && now.isBefore(d.getStartTime())) {
            return false;
        }
        return d.getEndTime() == null || !now.isAfter(d.getEndTime());
    }

    private boolean matchesScope(FlowDelegation d, String flowId, String nodeId) {
        String scope = d.getScope();
        if (scope == null || "ALL".equalsIgnoreCase(scope)) {
            return true;
        }
        if ("FLOW".equalsIgnoreCase(scope)) {
            return Objects.equals(d.getFlowId(), flowId);
        }
        if ("NODE".equalsIgnoreCase(scope)) {
            return Objects.equals(d.getFlowId(), flowId) && Objects.equals(d.getNodeId(), nodeId);
        }
        return false;
    }

    private int scopePriority(FlowDelegation d, String flowId, String nodeId) {
        String scope = d.getScope();
        if ("NODE".equalsIgnoreCase(scope) && Objects.equals(d.getFlowId(), flowId) && Objects.equals(d.getNodeId(), nodeId)) {
            return 3;
        }
        if ("FLOW".equalsIgnoreCase(scope) && Objects.equals(d.getFlowId(), flowId)) {
            return 2;
        }
        if (scope == null || "ALL".equalsIgnoreCase(scope)) {
            return 1;
        }
        return 0;
    }
}

