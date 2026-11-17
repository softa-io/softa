package io.softa.starter.flow.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.query.TaskInstanceContextEnricher;
import io.softa.starter.flow.service.support.FlowApprovalTaskProjector;
import io.softa.starter.flow.service.support.FlowInstanceAccessGuard;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FlowApprovalTaskServiceImpl#getTasksByInstanceId}: the tasks a request's own page reads say what their steps
 * allow, as My Tasks' rows do, so the page offers its approver the same actions. Without it the page offered Return on
 * a flow that allows none, refused only once clicked (zingkey-hcm#2752).
 */
class FlowApprovalTaskInstanceTasksTest {

    private final FlowInstanceService instances = mock(FlowInstanceService.class);
    private final FlowBundleRegistry bundles = mock(FlowBundleRegistry.class);
    private final FlowInstanceAccessGuard accessGuard = mock(FlowInstanceAccessGuard.class);
    private FlowApprovalTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        ApprovalAuditReader reader = new ApprovalAuditReader(new NoopApprovalActionLedger());
        service = spy(new FlowApprovalTaskServiceImpl(new FlowApprovalTaskProjector(reader), accessGuard, reader,
                new TaskInstanceContextEnricher(instances, mock(FlowUserNames.class), bundles), instances));
        doReturn(List.of(task(1L, "manager", "boss"), task(2L, "finance", "me")))
                .when(service).searchList(any(Filters.class));
        FlowInstance instance = new FlowInstance();
        instance.setInstanceId("i1");
        instance.setBundleId(9L);
        instance.setStatus(FlowExecutionStatus.WAITING);
        when(instances.findByInstanceId("i1")).thenReturn(Optional.of(instance));
    }

    @Test
    void aFlowThatAllowsNoReturnOffersNoneOnTheRequestsPage() {
        when(bundles.getByBundleId(9L)).thenReturn(Optional.of(definition(false)));

        List<FlowApprovalTaskView> tasks = service.getTasksByInstanceId("i1", "me");

        assertEquals(List.of(false, false), tasks.stream().map(FlowApprovalTaskView::getReturnAllowed).toList());
        assertEquals(List.of(false, true), tasks.stream().map(FlowApprovalTaskView::getFillIn).toList(),
                "a step with a required field is filled in and submitted, there as in the lists");
        verify(accessGuard).requireInstanceViewer("i1", "me", true);
    }

    @Test
    void aFlowThatAllowsReturnOffersItOnTheRequestsPage() {
        when(bundles.getByBundleId(9L)).thenReturn(Optional.of(definition(true)));

        List<FlowApprovalTaskView> tasks = service.getTasksByInstanceId("i1", "me");

        assertEquals(List.of(true, true), tasks.stream().map(FlowApprovalTaskView::getReturnAllowed).toList());
    }

    private static CompiledFlowDefinition definition(boolean returnEnabled) {
        return CompiledFlowDefinition.builder()
                .returnEnabled(returnEnabled)
                .nodeIndex(Map.of(
                        "manager", node("manager", new ApprovalNodeConfig()),
                        "finance", node("finance", ApprovalNodeConfig.builder()
                                .formPermissions(Map.of("decision", FormFieldPermission.REQUIRED)).build())))
                .build();
    }

    private static CompiledFlowNode node(String nodeId, ApprovalNodeConfig config) {
        return CompiledFlowNode.builder().nodeId(nodeId).parsedConfig(config).build();
    }

    private static FlowApprovalTask task(long id, String nodeId, String actorId) {
        FlowApprovalTask task = new FlowApprovalTask();
        task.setId(id);
        task.setInstanceId("i1");
        task.setNodeId(nodeId);
        task.setActorId(actorId);
        task.setTaskType(FlowApprovalTaskType.APPROVAL);
        task.setStatus(FlowApprovalTaskStatus.PENDING);
        return task;
    }
}
