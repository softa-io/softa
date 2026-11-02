package io.softa.starter.flow.service.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInstanceSummaryView;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.FormPermissionService;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.support.FlowUserNames;

/**
 * Batch-enriches approval task views with their owning instance's business
 * context (title / bound model / row id / execution status / initiator / submission
 * time, and what a record's approval kept of its record: subject, department,
 * effective date, number) — ONE `IN` query per page instead of an N+1 the frontend
 * cannot even perform itself (the runtime instance read is participant-scoped), every
 * user, employee and department named — the task's holder among them. The instances and names are read past the caller's
 * data permissions: the views were already authorized, as the caller's own tasks or by the
 * access guard.
 *
 * <p>Each view also carries what its step's configuration says about acting on it — whether it may
 * be returned, whether it may go in a batch — read from the revision the instance runs on (one bundle
 * lookup per distinct revision on the page), never from the record.
 */
@Component
public class TaskInstanceContextEnricher {

    private final FlowInstanceService instanceService;

    private final FlowUserNames names;

    private final FlowBundleRegistry bundleRegistry;

    public TaskInstanceContextEnricher(FlowInstanceService instanceService, FlowUserNames names,
                                       FlowBundleRegistry bundleRegistry) {
        this.instanceService = instanceService;
        this.names = names;
        this.bundleRegistry = bundleRegistry;
    }

    public void enrich(List<FlowApprovalTaskView> views) {
        if (views == null || views.isEmpty()) {
            return;
        }
        // One lookup for every account the page names: the holders, and who else each task names.
        Map<String, String> accountNames = names.namesOf(views.stream()
                .flatMap(view -> actorIdsOf(view).stream())
                .distinct()
                .toList());
        views.forEach(view -> {
            view.setActorName(accountNames.get(view.getActorId()));
            Map<String, String> named = new LinkedHashMap<>();
            actorIdsOf(view).forEach(id -> {
                String name = accountNames.get(id);
                if (name != null) {
                    named.put(id, name);
                }
            });
            view.setActorNames(named);
        });
        List<String> instanceIds = views.stream()
                .map(FlowApprovalTaskView::getInstanceId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (instanceIds.isEmpty()) {
            return;
        }
        Map<String, FlowInstance> byInstanceId = ContextUtils.withoutPermissionChecks(
                        () -> instanceService.findByInstanceIds(instanceIds)).stream()
                .collect(Collectors.toMap(FlowInstance::getInstanceId, Function.identity(), (a, b) -> a));
        List<FlowRecordApprovalsView.Actor> initiators = new ArrayList<>();
        List<FlowRecordApprovalsView.Actor> subjects = new ArrayList<>();
        List<FlowInstanceSummaryView.Department> departments = new ArrayList<>();
        Map<Long, Optional<CompiledFlowDefinition>> definitions = new HashMap<>();
        for (FlowApprovalTaskView view : views) {
            FlowInstance instance = byInstanceId.get(view.getInstanceId());
            if (instance == null) {
                // Retention cleanup may have removed the instance — the task row stands alone.
                continue;
            }
            view.setInstanceTitle(instance.getTitle());
            view.setModelName(instance.getModelName());
            view.setRowId(instance.getRowId());
            view.setInstanceStatus(instance.getStatus());
            view.setSubmittedTime(instance.getCreatedTime());
            view.setEffectiveDate(instance.getEffectiveDate());
            view.setRecordCode(instance.getRecordCode());
            if (instance.getInitiatorId() != null) {
                view.setInitiator(new FlowRecordApprovalsView.Actor(instance.getInitiatorId(), null));
                initiators.add(view.getInitiator());
            }
            if (instance.getSubjectId() != null) {
                view.setSubject(new FlowRecordApprovalsView.Actor(instance.getSubjectId().toString(), null));
                subjects.add(view.getSubject());
            }
            if (instance.getDepartmentId() != null) {
                view.setSubjectDept(new FlowInstanceSummaryView.Department(instance.getDepartmentId()));
                departments.add(view.getSubjectDept());
            }
            if (instance.getBundleId() != null) {
                definitions.computeIfAbsent(instance.getBundleId(), bundleRegistry::getByBundleId)
                        .map(definition -> definition.getNodeIndex().get(view.getNodeId()))
                        .ifPresent(node -> applyStepConfig(view, node));
            }
        }
        names.name(initiators);
        names.nameEmployees(subjects);
        names.nameDepartments(departments);
    }

    /** The accounts a task names: its holder, the step's candidates, who approved, rejected, closed or blocks it. */
    private static List<String> actorIdsOf(FlowApprovalTaskView view) {
        List<String> ids = new ArrayList<>();
        ids.add(view.getActorId());
        Stream.of(view.getCandidateActors(), view.getApprovedActors(), view.getRejectedActors())
                .filter(Objects::nonNull)
                .forEach(ids::addAll);
        ids.add(view.getClosedByActorId());
        ids.add(view.getBlockedByActorId());
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    /**
     * A return-enabled step, as the node declares it; a step to fill in when it makes fields required (a handling
     * step); batchable only while the task is open to its approver (pending, its add-sign prerequisite met, the
     * instance waiting) and it is no step to fill in — a batch fills in nothing.
     */
    private static void applyStepConfig(FlowApprovalTaskView view, CompiledFlowNode node) {
        if (!(node.getParsedConfig() instanceof ApprovalNodeConfig cfg)) {
            return;
        }
        view.setReturnAllowed(Boolean.TRUE.equals(cfg.getReturnEnabled()));
        boolean fillIn = !FormPermissionService.requiredFields(node).isEmpty();
        view.setFillIn(fillIn);
        boolean open = view.getStatus() == FlowApprovalTaskStatus.PENDING
                && view.getTaskType() == FlowApprovalTaskType.APPROVAL
                && !Boolean.TRUE.equals(view.getBlocked())
                && view.getInstanceStatus() == FlowExecutionStatus.WAITING;
        view.setBatchable(open && !fillIn);
    }
}
