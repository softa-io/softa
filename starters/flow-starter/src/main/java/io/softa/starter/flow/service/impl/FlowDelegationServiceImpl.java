package io.softa.starter.flow.service.impl;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.flow.dto.FlowDelegationFlowView;
import io.softa.starter.flow.dto.FlowDelegationMonitorSearchRequest;
import io.softa.starter.flow.dto.FlowDelegationSearchRequest;
import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.enums.FlowDelegationStatus;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.ActorAccounts;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.service.FlowDelegationService;
import io.softa.starter.flow.service.FlowDesignService;
import io.softa.starter.flow.service.support.CallerConditions;

/**
 * ORM-backed delegation service.
 */
@Service
public class FlowDelegationServiceImpl extends EntityServiceImpl<FlowDelegation, Long>
        implements FlowDelegationService {

    private static final String DESIGN_ID = LambdaUtils.getAttributeName(FlowDesign::getId);

    private static final String DESIGN_NAME = LambdaUtils.getAttributeName(FlowDesign::getFlowName);

    /** What the administrators' list filters a rule's status by: no column holds it ({@link FlowDelegationStatus}). */
    private static final String STATUS = "status";

    /** Whether the delegate can act; without it, anyone can. */
    @Autowired(required = false)
    private ActorAccounts actorAccounts;

    // Looked up when used: the engine reads delegations, and the flows lead back to the engine.
    @Autowired(required = false)
    private ObjectProvider<FlowBundleRegistry> bundleRegistry;

    @Autowired(required = false)
    private ObjectProvider<FlowDesignService> designService;

    /**
     * A rule routing the delegator's tasks to someone else who can act over its period — from its start time, or its
     * creation without one, until its end time or its cancellation ({@link FlowDelegationStatus}) — a period that
     * ends after it starts when it has both times, scoped to a flow or one of its approval steps that is in use. One
     * the delegator makes and one an administrator makes for them alike.
     */
    @Override
    public FlowDelegation createDelegation(FlowDelegation delegation) {
        if (delegation == null || !StringUtils.hasText(delegation.getDelegatorId())
                || !StringUtils.hasText(delegation.getDelegateId())) {
            throw new FlowActionValidationException("Both the approver and the delegate are required");
        }
        if (delegation.getDelegatorId().equals(delegation.getDelegateId())) {
            throw new FlowActionValidationException("The delegate must be someone else");
        }
        if (delegation.getStartTime() != null && delegation.getEndTime() != null
                && !delegation.getEndTime().isAfter(delegation.getStartTime())) {
            throw new FlowActionValidationException("The delegation must end after it starts");
        }
        requireScopeInUse(delegation);
        // A task delegated to someone who cannot act would wait on nobody: the rule would never apply.
        if (actorAccounts != null) {
            actorAccounts.requireUsable(List.of(delegation.getDelegateId()));
        }
        // Stored without the flag, a rule was never in force: the lookup takes only active ones.
        delegation.setActive(true);
        Long id = this.createOne(delegation);
        delegation.setId(id);
        return delegation;
    }

    /**
     * A rule of scope {@code Flow} or {@code Node} names one of {@link #listDelegableFlows()} — and, for a node, one of
     * its approval steps; one naming anything else would never apply.
     */
    private void requireScopeInUse(FlowDelegation delegation) {
        String scope = delegation.getScope();
        boolean node = "NODE".equalsIgnoreCase(scope);
        if (!node && !"FLOW".equalsIgnoreCase(scope)) {
            return;
        }
        if (!StringUtils.hasText(delegation.getFlowId())) {
            throw new FlowActionValidationException("Choose the approval flow the delegation is for");
        }
        if (node && !StringUtils.hasText(delegation.getNodeId())) {
            throw new FlowActionValidationException("Choose the approval step the delegation is for");
        }
        FlowBundleRegistry registry = bundleRegistry == null ? null : bundleRegistry.getIfAvailable();
        if (registry == null) {
            return;
        }
        // The tenant's flows, whoever creates the rule: an employee's row scope on the flow models reaches none.
        FlowDelegationFlowView flow = ContextUtils.withoutPermissionChecks(this::listDelegableFlows).stream()
                .filter(candidate -> candidate.flowId().equals(delegation.getFlowId()))
                .findFirst()
                .orElseThrow(() -> new FlowActionValidationException(
                        "Flow " + delegation.getFlowId() + " is not an approval flow in use"));
        if (node && flow.nodes().stream().noneMatch(step -> step.nodeId().equals(delegation.getNodeId()))) {
            throw new FlowActionValidationException("Flow '" + flow.flowName() + "' has no approval step "
                    + delegation.getNodeId());
        }
    }

    @Override
    public List<FlowDelegationFlowView> listDelegableFlows() {
        FlowBundleRegistry registry = bundleRegistry == null ? null : bundleRegistry.getIfAvailable();
        if (registry == null) {
            return List.of();
        }
        // The effective revisions of the tenant's enabled flows: an approval flow without one starts nothing.
        List<CompiledFlowDefinition> flows = registry.list().stream()
                .filter(definition -> FlowScenario.APPROVAL.equals(definition.getScenario())
                        && StringUtils.hasText(definition.getFlowId()))
                .toList();
        Map<String, String> names = flowNames(flows.stream().map(CompiledFlowDefinition::getFlowId).toList());
        return flows.stream()
                .map(definition -> new FlowDelegationFlowView(definition.getFlowId(),
                        names.getOrDefault(definition.getFlowId(), definition.getFlowName()), approvalSteps(definition)))
                .sorted(Comparator.comparing(FlowDelegationFlowView::flowName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** The flows' names as they are now, by flow id: a revision keeps the name it was published under. */
    private Map<String, String> flowNames(List<String> flowIds) {
        FlowDesignService designs = designService == null ? null : designService.getIfAvailable();
        Map<String, String> names = new HashMap<>();
        if (designs == null || flowIds.isEmpty()) {
            return names;
        }
        for (FlowDesign design : designs.getByIds(flowIds, List.of(DESIGN_ID, DESIGN_NAME))) {
            if (StringUtils.hasText(design.getFlowName())) {
                names.put(design.getId(), design.getFlowName());
            }
        }
        return names;
    }

    /** The revision's approval steps in flow order. */
    private static List<FlowDelegationFlowView.Node> approvalSteps(CompiledFlowDefinition definition) {
        Map<String, CompiledFlowNode> nodes = definition.getNodeIndex() == null ? Map.of() : definition.getNodeIndex();
        List<String> order = definition.getTopologicalOrder() != null && !definition.getTopologicalOrder().isEmpty()
                ? definition.getTopologicalOrder() : List.copyOf(nodes.keySet());
        return order.stream()
                .map(nodes::get)
                .filter(node -> node != null && FlowNodeType.APPROVAL.equals(node.getType()))
                .map(node -> new FlowDelegationFlowView.Node(node.getNodeId(), node.displayName()))
                .toList();
    }

    /** One delegator's rules in force at {@code now} — feeds the scope-aware single lookup below. */
    private List<FlowDelegation> findActiveDelegations(String delegatorId, LocalDateTime now) {
        Filters filters = new Filters()
                .eq(FlowDelegation::getDelegatorId, delegatorId)
                .eq(FlowDelegation::getActive, true);
        return this.searchList(filters).stream()
                .filter(d -> FlowDelegationStatus.of(d, now) == FlowDelegationStatus.ACTIVE)
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
    public void cancelDelegation(Long delegationId, String requesterId) {
        FlowDelegation delegation = this.getById(delegationId)
                .filter(d -> requesterId != null && requesterId.equals(d.getDelegatorId()))
                .orElseThrow(() -> new FlowAuthorizationException(
                        "You have no delegation " + delegationId + " to cancel"));
        writeInactive(delegation);
    }

    @Override
    public Page<FlowDelegation> searchAll(FlowDelegationMonitorSearchRequest request) {
        FlowDelegationMonitorSearchRequest query = request != null ? request
                : new FlowDelegationMonitorSearchRequest(null, null, null, null);
        Filters filters = withStatusConditions(query.filters(), LocalDateTime.now());
        CallerConditions.requireStoredFields(modelName, filters, query.orders());
        Orders orders = CallerConditions.ordersOr(query.orders(), Orders.ofDesc(FlowDelegation::getId));
        return this.searchPage(new FlexQuery(Filters.isEmpty(filters) ? new Filters() : filters, orders),
                Page.of(query.pageNumber(), query.pageSize()));
    }

    /**
     * The filters with each condition on {@link #STATUS} replaced, where it stands, by the condition on the stored
     * fields that gives that status at {@code now} ({@link FlowDelegationStatus#condition}); the rest as they are.
     */
    private static Filters withStatusConditions(Filters filters, LocalDateTime now) {
        if (filters == null) {
            return null;
        }
        if (FilterType.TREE.equals(filters.getType())) {
            filters.getChildren().replaceAll(child -> withStatusConditions(child, now));
            return filters;
        }
        FilterUnit unit = filters.getFilterUnit();
        if (!FilterType.LEAF.equals(filters.getType()) || unit.isTuple() || !STATUS.equals(unit.getField())) {
            return filters;
        }
        if (unit.getOperator() != Operator.EQUAL) {
            throw new FlowActionValidationException("Filter by 'status' with =");
        }
        Object value = unit.getValue();
        return Arrays.stream(FlowDelegationStatus.values())
                .filter(status -> status.getType().equals(value))
                .findFirst()
                .orElseThrow(() -> new FlowActionValidationException("No delegation status '" + value
                        + "': Scheduled, Active, Ended or Cancelled"))
                .condition(now);
    }

    @Override
    public void deactivate(Long delegationId) {
        FlowDelegation delegation = this.getById(delegationId)
                .orElseThrow(() -> new FlowActionValidationException("No delegation " + delegationId + " to cancel"));
        writeInactive(delegation);
    }

    @Override
    public Page<FlowDelegation> searchDelegations(String userId, FlowDelegationSearchRequest request) {
        if (!StringUtils.hasText(userId)) {
            throw new IllegalArgumentException("userId is required for a delegation search");
        }
        if (request == null || request.box() == null) {
            throw new FlowActionValidationException("Choose the delegations to search: Mine or ToMe");
        }
        CallerConditions.requireStoredFields(modelName, request.filters(), request.orders());
        // The box pins the rows to the user; the caller's filters only narrow them.
        Filters own = switch (request.box()) {
            case MINE -> new Filters().eq(FlowDelegation::getDelegatorId, userId);
            case TO_ME -> toMeFilters(userId, LocalDateTime.now());
        };
        Orders orders = CallerConditions.ordersOr(request.orders(), Orders.ofDesc(FlowDelegation::getId));
        return this.searchPage(new FlexQuery(CallerConditions.and(own, request.filters()), orders),
                Page.of(request.pageNumber(), request.pageSize()));
    }

    /**
     * The rules routing others' tasks to the delegate, now or later — every status but {@code Ended} and
     * {@code Cancelled} ({@link FlowDelegationStatus#of}), as a condition, so the page holds what the box lists.
     */
    private static Filters toMeFilters(String delegateId, LocalDateTime now) {
        Filters notEnded = Filters.or(new Filters().isNotSet(FlowDelegation::getEndTime),
                new Filters().ge(FlowDelegation::getEndTime, now));
        return new Filters()
                .eq(FlowDelegation::getDelegateId, delegateId)
                .eq(FlowDelegation::getActive, true)
                .and(notEnded);
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

