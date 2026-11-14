package io.softa.starter.flow.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowDelegationFlowView;
import io.softa.starter.flow.dto.FlowDelegationMonitorSearchRequest;
import io.softa.starter.flow.dto.FlowDelegationSearchRequest;
import io.softa.starter.flow.entity.FlowDelegation;

/**
 * Service interface for delegation rule CRUD and queries.
 */
public interface FlowDelegationService {

    /**
     * Create a delegation rule and return the saved entity.
     */
    FlowDelegation createDelegation(FlowDelegation delegation);

    /**
     * The approval flows a delegation rule can be scoped to: every enabled Approval flow of the current tenant with
     * an effective revision, with that revision's approval steps, by flow name. Read with the caller's permissions;
     * the endpoint serving it waives row scope.
     */
    List<FlowDelegationFlowView> listDelegableFlows();

    /**
     * The delegator's rule that routes a task arriving at {@code now} — one
     * {@link io.softa.starter.flow.enums.FlowDelegationStatus#ACTIVE ACTIVE} then, the most specific scope first:
     * node, then flow, then all.
     */
    Optional<FlowDelegation> findActiveDelegation(String delegatorId, String flowId, String nodeId, LocalDateTime now);

    /**
     * Cancel (deactivate) one of the requester's delegations.
     *
     * @throws io.softa.starter.flow.runtime.exception.FlowAuthorizationException when the requester
     *         has no delegation of that id — another user's and a missing one are not told apart
     */
    void cancelDelegation(Long delegationId, String requesterId);

    /**
     * One page of every delegation rule of the tenant — for an administrator, authorized by the caller beforehand —
     * narrowed by the request's filters and sorted by its orders, newest first unless they sort otherwise. Filters
     * name stored fields of {@code FlowDelegation}, or {@code status} with {@code =}: the rules in that status now
     * ({@link io.softa.starter.flow.enums.FlowDelegationStatus#condition}); orders, stored fields only.
     *
     * @throws io.softa.starter.flow.runtime.exception.FlowActionValidationException when a filter or an order names
     *         anything else, or a status is filtered by with another operator or one there is not
     */
    Page<FlowDelegation> searchAll(FlowDelegationMonitorSearchRequest request);

    /** Deactivates any delegation rule — for an administrator, authorized by the caller beforehand. */
    void deactivate(Long delegationId);

    /**
     * One page of a user's own rules in a box — the ones routing their tasks, or the ones routing others' tasks to
     * them — narrowed by the request's filters and sorted by its orders, on stored fields of {@code FlowDelegation}
     * only. Pinned to the user, so read past row scope by the endpoint serving it.
     *
     * @throws io.softa.starter.flow.runtime.exception.FlowActionValidationException without a box, or when a filter
     *         or an order names anything but a stored field
     */
    Page<FlowDelegation> searchDelegations(String userId, FlowDelegationSearchRequest request);

    /**
     * Increment the delegated task count and update last delegation time.
     */
    void recordDelegationUsage(Long delegationId);
}

