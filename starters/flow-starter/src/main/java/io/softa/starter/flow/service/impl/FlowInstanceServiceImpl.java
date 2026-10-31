package io.softa.starter.flow.service.impl;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.annotation.SkipPermissionCheck;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.FormPermissionService;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.store.ApprovalActionLedger;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.support.FlowUserNames;

/**
 * ORM-backed flow instance service.
 */
@Service
public class FlowInstanceServiceImpl extends EntityServiceImpl<FlowInstance, Long>
        implements FlowInstanceService {

    @Autowired
    private FlowBundleRegistry bundleRegistry;

    @Autowired
    private ApprovalActionLedger approvalLedger;

    @Autowired
    private FlowUserNames userNames;

    /** Absent without a permission system: then no field is hidden from anyone. */
    @Autowired(required = false)
    private PermissionService permissionService;

    /** The states of an instance that has not ended. */
    private static final List<FlowExecutionStatus> UNFINISHED = Arrays.stream(FlowExecutionStatus.values())
            .filter(status -> !status.isTerminal())
            .toList();

    /** Instance search projection — the heavy JSON state columns are deliberately excluded. */
    private static final List<String> INSTANCE_SUMMARY_FIELDS = List.of(
            LambdaUtils.getAttributeName(FlowInstance::getId),
            LambdaUtils.getAttributeName(FlowInstance::getInstanceId),
            LambdaUtils.getAttributeName(FlowInstance::getBundleId),
            LambdaUtils.getAttributeName(FlowInstance::getFlowId),
            LambdaUtils.getAttributeName(FlowInstance::getFlowName),
            LambdaUtils.getAttributeName(FlowInstance::getScenario),
            LambdaUtils.getAttributeName(FlowInstance::getFlowRevision),
            LambdaUtils.getAttributeName(FlowInstance::getTitle),
            LambdaUtils.getAttributeName(FlowInstance::getModelName),
            LambdaUtils.getAttributeName(FlowInstance::getRowId),
            LambdaUtils.getAttributeName(FlowInstance::getInitiatorId),
            LambdaUtils.getAttributeName(FlowInstance::getRecordApproval),
            LambdaUtils.getAttributeName(FlowInstance::getSubjectId),
            LambdaUtils.getAttributeName(FlowInstance::getDepartmentId),
            LambdaUtils.getAttributeName(FlowInstance::getEffectiveDate),
            LambdaUtils.getAttributeName(FlowInstance::getRecordCode),
            LambdaUtils.getAttributeName(FlowInstance::getSuperseded),
            LambdaUtils.getAttributeName(FlowInstance::getStatus),
            LambdaUtils.getAttributeName(FlowInstance::getFailedNodeId),
            LambdaUtils.getAttributeName(FlowInstance::getNextFireAt),
            LambdaUtils.getAttributeName(FlowInstance::getCreatedTime),
            LambdaUtils.getAttributeName(FlowInstance::getUpdatedTime));

    @Override
    public FlowInstance saveInstance(FlowInstance instance) {
        if (instance.getId() != null) {
            if (this.updateOne(instance, false)) {
                advanceVersion(instance);
            }
            return instance;
        }
        // Check if one already exists by instanceId
        Optional<FlowInstance> existing = findByInstanceId(instance.getInstanceId());
        if (existing.isPresent()) {
            instance.setId(existing.get().getId());
            if (instance.getVersion() == null) {
                instance.setVersion(existing.get().getVersion());
            }
            if (this.updateOne(instance, false)) {
                advanceVersion(instance);
            }
            return instance;
        }
        Long id = this.createOne(instance);
        instance.setId(id);
        if (instance.getVersion() == null) {
            instance.setVersion(1);
        }
        return instance;
    }

    private static void advanceVersion(FlowInstance instance) {
        if (instance.getVersion() != null) {
            instance.setVersion(instance.getVersion() + 1);
        }
    }

    @Override
    public Optional<FlowInstance> findByInstanceId(String instanceId) {
        Filters filters = new Filters().eq(FlowInstance::getInstanceId, instanceId);
        return this.searchOne(filters);
    }

    @Override
    public List<FlowInstance> findByInstanceIds(Collection<String> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return List.of();
        }
        Filters filters = new Filters().in(FlowInstance::getInstanceId, List.copyOf(instanceIds));
        FlexQuery query = new FlexQuery(filters);
        query.select(INSTANCE_SUMMARY_FIELDS);
        return this.searchList(query);
    }

    @Override
    public List<FlowInstance> findByFlowId(String flowId) {
        Filters filters = new Filters().eq(FlowInstance::getFlowId, flowId);
        return this.searchList(filters);
    }

    @Override
    public List<FlowInstance> findByStatus(FlowExecutionStatus status) {
        Filters filters = new Filters().eq(FlowInstance::getStatus, status);
        return this.searchList(filters);
    }

    @Override
    public List<FlowInstance> findByModelNameAndRowId(String modelName, String rowId) {
        Filters filters = new Filters()
                .eq(FlowInstance::getModelName, modelName)
                .eq(FlowInstance::getRowId, rowId);
        return this.searchList(filters);
    }

    @Override
    public Optional<FlowInstance> findByTriggerEvent(String flowId, String eventId) {
        Filters filters = new Filters()
                .eq(FlowInstance::getFlowId, flowId)
                .eq(FlowInstance::getTriggerEventId, eventId);
        return this.searchOne(filters);
    }

    @Override
    public List<FlowInstance> findByInitiatorId(String initiatorId) {
        Filters filters = new Filters().eq(FlowInstance::getInitiatorId, initiatorId);
        return this.searchList(filters);
    }

    @Override
    public List<FlowInstance> findDueTimers(LocalDateTime now, int limit) {
        Filters filters = new Filters()
                .eq(FlowInstance::getStatus, FlowExecutionStatus.WAITING)
                .le(FlowInstance::getNextFireAt, now);
        Orders orders = Orders.ofAsc(FlowInstance::getNextFireAt);
        FlexQuery query = new FlexQuery(filters, orders);
        query.setLimitSize(limit);
        return this.searchList(query);
    }

    @Override
    public long countDueTimers(LocalDateTime now) {
        Filters filters = new Filters()
                .eq(FlowInstance::getStatus, FlowExecutionStatus.WAITING)
                .le(FlowInstance::getNextFireAt, now);
        return this.count(filters);
    }

    @Override
    public long countByStatus(FlowExecutionStatus status) {
        Filters filters = new Filters().eq(FlowInstance::getStatus, status);
        return this.count(filters);
    }

    @Override
    @SkipPermissionCheck
    public long countUnfinished(String flowId) {
        return this.count(new Filters().eq(FlowInstance::getFlowId, flowId).in(FlowInstance::getStatus, UNFINISHED));
    }

    @Override
    @SkipPermissionCheck
    public Set<Long> unfinishedBundleIds() {
        FlexQuery query = new FlexQuery(new Filters().in(FlowInstance::getStatus, UNFINISHED))
                .select(FlowInstance::getBundleId);
        query.setDistinct(true);
        return this.searchList(query).stream()
                .map(FlowInstance::getBundleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    @Override
    public Page<FlowInstance> searchInstances(FlexQuery query, Page<FlowInstance> page) {
        return this.searchPage(query, page);
    }

    @Override
    public Page<FlowInstance> searchSummaries(FlowInstanceSearchRequest request, String forcedInitiatorId) {
        Filters filters = new Filters();
        if (request.flowId() != null) {
            filters.eq(FlowInstance::getFlowId, request.flowId());
        }
        if (request.status() != null) {
            filters.eq(FlowInstance::getStatus, request.status());
        }
        String initiatorId = forcedInitiatorId != null ? forcedInitiatorId : request.initiatorId();
        if (initiatorId != null) {
            filters.eq(FlowInstance::getInitiatorId, initiatorId);
        }
        if (forcedInitiatorId != null) {
            // The caller's own list is what they submitted: their records' approvals. An automation run
            // their write happened to trigger records them as initiator too, but is not theirs to follow.
            filters.eq(FlowInstance::getRecordApproval, true);
        }
        if (request.modelName() != null) {
            filters.eq(FlowInstance::getModelName, request.modelName());
        }
        if (request.rowId() != null) {
            filters.eq(FlowInstance::getRowId, request.rowId());
        }
        if (request.createdFrom() != null) {
            filters.ge(FlowInstance::getCreatedTime, request.createdFrom());
        }
        if (request.createdTo() != null) {
            filters.le(FlowInstance::getCreatedTime, request.createdTo());
        }
        if (request.subjectId() != null) {
            filters.eq(FlowInstance::getSubjectId, request.subjectId());
        }
        if (request.subjectDeptId() != null) {
            filters.eq(FlowInstance::getDepartmentId, request.subjectDeptId());
        }
        if (request.effectiveDateFrom() != null) {
            filters.ge(FlowInstance::getEffectiveDate, request.effectiveDateFrom());
        }
        if (request.effectiveDateTo() != null) {
            filters.le(FlowInstance::getEffectiveDate, request.effectiveDateTo());
        }
        if (StringUtils.hasText(request.recordCode())) {
            filters.contains(FlowInstance::getRecordCode, request.recordCode().trim());
        }
        if (Boolean.TRUE.equals(request.awaitingRevision())) {
            // Returned and not replaced by a later submission: the record still waits to be edited.
            filters.eq(FlowInstance::getStatus, FlowExecutionStatus.RETURNED)
                    .ne(FlowInstance::getSuperseded, true);
        }
        FlexQuery query = new FlexQuery(filters, Orders.ofDesc(FlowInstance::getCreatedTime));
        query.select(INSTANCE_SUMMARY_FIELDS);
        Page<FlowInstance> page = Page.of(
                request.pageNumber() == null ? 1 : request.pageNumber(),
                request.pageSize() == null ? 50 : request.pageSize());
        // Funnel through searchInstances so a replacement implementation that
        // intercepts raw monitoring queries sees this path too.
        return this.searchInstances(query, page);
    }

    @Override
    public void markSuperseded(String modelName, String rowId) {
        FlowInstance superseded = new FlowInstance();
        superseded.setSuperseded(true);
        Filters filters = new Filters()
                .eq(FlowInstance::getModelName, modelName)
                .eq(FlowInstance::getRowId, rowId)
                .eq(FlowInstance::getRecordApproval, true)
                .ne(FlowInstance::getSuperseded, true);
        ContextUtils.withoutPermissionChecks(() -> this.updateByFilter(filters, superseded));
    }

    @Override
    public FlowRecordApprovalsView recordApprovals(String modelName, String rowId,
                                                   Consumer<FlowRecordApprovalsView> authorize) {
        List<FlowRecordApprovalsView.Instance> instances = ContextUtils.withoutPermissionChecks(
                () -> findByModelNameAndRowId(modelName, rowId).stream()
                        // Other instances may run on the record — automation, debug runs: not its approval.
                        .filter(instance -> Boolean.TRUE.equals(instance.getRecordApproval()))
                        .sorted(Comparator.comparing(FlowInstance::getCreatedTime,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .map(this::approvalRun)
                        .map(ApprovalRun::toView)
                        .toList());
        FlowRecordApprovalsView history = new FlowRecordApprovalsView(instances);
        authorize.accept(history);
        nameUsers(history);
        return history;
    }

    private ApprovalRun approvalRun(FlowInstance instance) {
        CompiledFlowDefinition definition = bundleRegistry.getByBundleId(instance.getBundleId()).orElse(null);
        List<PendingApproval> pending = FlowExecutionStatus.WAITING.equals(instance.getStatus())
                ? pendingApprovalsOf(instance)
                : List.of();
        List<ApprovalActionAuditEntry> actions = approvalLedger.findByInstanceId(instance.getInstanceId()).stream()
                .filter(entry -> !ApprovalActionType.READ.equals(entry.getAction()))
                .toList();
        return new ApprovalRun(instance, definition == null ? null : definition.getFlowName(), pending, actions,
                failureOf(instance, definition));
    }

    /**
     * Where and why a failed approval stopped, or null for one that did not fail. The record stays in
     * approval with nobody to act on it, so the panel says so and that its submitter takes it back.
     */
    private static FlowRecordApprovalsView.Failure failureOf(FlowInstance instance, CompiledFlowDefinition definition) {
        if (!FlowExecutionStatus.FAILED.equals(instance.getStatus())) {
            return null;
        }
        String nodeId = instance.getFailedNodeId();
        CompiledFlowNode node = nodeId == null || definition == null || definition.getNodeIndex() == null
                ? null : definition.getNodeIndex().get(nodeId);
        return new FlowRecordApprovalsView.Failure(nodeId, node == null ? null : node.getLabel(), instance.getErrorMessage());
    }

    /** Names every user the history mentions — done once the caller is known to be allowed to see it. */
    private void nameUsers(FlowRecordApprovalsView history) {
        List<FlowRecordApprovalsView.Actor> actors = new ArrayList<>();
        for (FlowRecordApprovalsView.Instance instance : history.getInstances()) {
            actors.add(instance.getInitiator());
            instance.getPending().forEach(step -> actors.addAll(step.getApprovers()));
            instance.getActions().forEach(action -> {
                actors.add(action.getActor());
                actors.add(action.getTarget());
            });
        }
        userNames.name(actors);
    }

    @Override
    public Optional<Map<String, Object>> recordOf(FlowExecutionState state, String viewerId) {
        String modelName = state.getModelName();
        if (!StringUtils.hasText(modelName) || !StringUtils.hasText(state.getRowId())
                || !ModelManager.existModel(modelName)) {
            return Optional.empty();
        }
        Serializable id = IdUtils.formatId(modelName, state.getRowId());
        @SuppressWarnings("unchecked")
        ModelService<Serializable> records = (ModelService<Serializable>) (ModelService<?>) modelService;
        // Asked as the viewer, before the read below sets every permission check aside.
        Set<String> unseen = permissionService == null ? Set.of()
                : permissionService.getHiddenSensitiveFields().getOrDefault(modelName, Set.of());
        RecordFields shown = recordFields(state, viewerId, unseen);
        // The record as its detail page shows it: every stored field, every dynamic one (cascaded and
        // computed), each one-to-many's rows in full (an expense claim's lines), a many-to-many as
        // references. REFERENCE, as the model read API returns it: relations and options come with their
        // display names.
        List<String> fields = new ArrayList<>(ModelManager.getModelStoredFields(modelName));
        SubQueries childRows = new SubQueries();
        for (MetaField field : ModelManager.getModelFields(modelName)) {
            if (FieldType.TO_MANY_TYPES.contains(field.getFieldType())) {
                fields.add(field.getFieldName());
                if (FieldType.ONE_TO_MANY.equals(field.getFieldType())) {
                    childRows.expand(field.getFieldName(), new SubQuery());
                }
            } else if (field.isDynamic()) {
                fields.add(field.getFieldName());
            }
        }
        return ContextUtils.withoutPermissionChecks(
                        () -> records.getById(modelName, id, fields, childRows, ConvertType.REFERENCE))
                .map(row -> {
                    Map<String, Object> visible = new LinkedHashMap<>();
                    row.forEach((field, value) -> {
                        if (shown.includes(field)) {
                            visible.put(field, value);
                        }
                    });
                    return visible;
                });
    }

    /**
     * The record fields the viewer is shown. By default, every field but the sensitive ones the viewer may
     * see on no row ({@code unseen}). The waiting steps the viewer is an approver of lay their field
     * permissions over that: a field they list as Readonly, Editable or Required is shown, sensitive or
     * not — the designer granted it — and one they mark Hidden is not; with several such steps, a field
     * any of them shows is shown. The initiator sees the default. Anyone else — a CC recipient, an earlier
     * approver — sees the default without whatever an approval step of the flow hides.
     */
    private RecordFields recordFields(FlowExecutionState state, String viewerId, Set<String> unseen) {
        CompiledFlowDefinition definition = bundleRegistry.getByBundleId(state.getBundleId()).orElse(null);
        if (definition == null || definition.getNodeIndex() == null) {
            return new RecordFields(null, unseen, Set.of());
        }
        List<PendingApproval> pending = state.getPendingApprovals() == null ? List.of() : state.getPendingApprovals();
        List<Map<String, FormFieldPermission>> ownSteps = pending.stream()
                .filter(step -> step.getApprovers() != null && step.getApprovers().contains(viewerId))
                .map(step -> definition.getNodeIndex().get(step.getNodeId()))
                .filter(Objects::nonNull)
                .map(FormPermissionService::getFieldPermissions)
                .toList();
        if (!ownSteps.isEmpty()) {
            return new RecordFields(ownSteps, unseen, Set.of());
        }
        if (Objects.equals(viewerId, state.getInitiatorId())) {
            return new RecordFields(null, unseen, Set.of());
        }
        Set<String> hidden = new HashSet<>();
        definition.getNodeIndex().values().stream()
                .filter(node -> FlowNodeType.APPROVAL.equals(node.getType()))
                .forEach(node -> FormPermissionService.getFieldPermissions(node).forEach((field, permission) -> {
                    if (FormFieldPermission.HIDDEN.equals(permission)) {
                        hidden.add(field);
                    }
                }));
        return new RecordFields(null, unseen, hidden);
    }

    /**
     * The fields of a record that are shown: through the viewer's own steps' permissions
     * ({@code ownSteps}, null for a viewer with none), else every field but the {@code unseen} and the
     * {@code hidden}. The id always.
     */
    private record RecordFields(List<Map<String, FormFieldPermission>> ownSteps, Set<String> unseen,
                                Set<String> hidden) {

        boolean includes(String field) {
            if (ModelConstant.ID.equals(field)) {
                return true;
            }
            if (ownSteps != null) {
                return ownSteps.stream().anyMatch(permissions -> {
                    FormFieldPermission permission = permissions.get(field);
                    return permission == null ? !unseen.contains(field) : permission != FormFieldPermission.HIDDEN;
                });
            }
            return !unseen.contains(field) && !hidden.contains(field);
        }
    }

    private static List<PendingApproval> pendingApprovalsOf(FlowInstance instance) {
        return JsonUtils.stringToObject(instance.getPendingApprovals(),
                new TypeReference<List<PendingApproval>>() {}, new ArrayList<>());
    }

    /** The approvers of a waiting step who have not acted on it yet. */
    private static List<String> stillToAct(PendingApproval step) {
        List<String> approvers = step.getApprovers() == null ? List.of() : step.getApprovers();
        return approvers.stream()
                .filter(approver -> step.getApprovedActors() == null || !step.getApprovedActors().contains(approver))
                .filter(approver -> step.getRejectedActors() == null || !step.getRejectedActors().contains(approver))
                .toList();
    }

    /** One approval instance of a record with what its history view is built from. */
    private record ApprovalRun(FlowInstance instance, String flowName, List<PendingApproval> pending,
                               List<ApprovalActionAuditEntry> actions, FlowRecordApprovalsView.Failure failure) {

        /** The view, its users as ids; {@link #nameUsers} names them. */
        FlowRecordApprovalsView.Instance toView() {
            return FlowRecordApprovalsView.Instance.builder()
                    .instanceId(instance.getInstanceId())
                    .flowId(instance.getFlowId())
                    .flowName(flowName)
                    .status(instance.getStatus())
                    .initiator(actor(instance.getInitiatorId()))
                    .startedAt(instance.getCreatedTime())
                    .updatedAt(instance.getUpdatedTime())
                    .pending(pending.stream().map(step -> FlowRecordApprovalsView.PendingStep.builder()
                            .nodeId(step.getNodeId())
                            .nodeLabel(step.getNodeLabel())
                            .approvers(stillToAct(step).stream().map(ApprovalRun::actor).toList())
                            .build()).toList())
                    .actions(actions.stream().map(entry -> FlowRecordApprovalsView.Action.builder()
                            .action(entry.getAction())
                            .nodeLabel(entry.getNodeLabel())
                            .actor(actor(entry.getActorId()))
                            .target(actor(entry.getTargetActorId()))
                            .comment(entry.getComment())
                            .eventTime(entry.getEventTime())
                            .build()).toList())
                    .failure(failure)
                    .build();
        }

        private static FlowRecordApprovalsView.Actor actor(String id) {
            return id == null ? null : new FlowRecordApprovalsView.Actor(id, null);
        }
    }

    @Override
    public List<FlowInstance> findStuckInstances(LocalDateTime threshold, int limit) {
        Filters filters = new Filters()
                .in(FlowInstance::getStatus, List.of(
                        FlowExecutionStatus.RUNNING,
                        FlowExecutionStatus.WAITING))
                .lt(FlowInstance::getUpdatedTime, threshold);
        Orders orders = Orders.ofAsc(FlowInstance::getUpdatedTime);
        FlexQuery query = new FlexQuery(filters, orders);
        query.setLimitSize(limit);
        return this.searchList(query);
    }
}
