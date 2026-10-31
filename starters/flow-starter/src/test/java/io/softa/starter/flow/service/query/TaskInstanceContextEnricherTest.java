package io.softa.starter.flow.service.query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowInstanceService;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TaskInstanceContextEnricher} — one batched instance lookup
 * per page, merged onto the task views by instanceId.
 */
class TaskInstanceContextEnricherTest {

    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    private final FlowUserNames names = mock(FlowUserNames.class);
    private final FlowBundleRegistry bundleRegistry = mock(FlowBundleRegistry.class);
    private final TaskInstanceContextEnricher enricher =
            new TaskInstanceContextEnricher(instanceService, names, bundleRegistry);

    private static FlowInstance instance(String instanceId, String title, String modelName, String rowId) {
        FlowInstance instance = new FlowInstance();
        instance.setInstanceId(instanceId);
        instance.setTitle(title);
        instance.setModelName(modelName);
        instance.setRowId(rowId);
        instance.setStatus(FlowExecutionStatus.WAITING);
        return instance;
    }

    private static FlowApprovalTaskView view(String instanceId) {
        return FlowApprovalTaskView.builder().instanceId(instanceId).build();
    }

    @Test
    void enrichMergesInstanceContextByInstanceId() {
        when(instanceService.findByInstanceIds(argThat(ids -> ids.containsAll(List.of("i1", "i2")))))
                .thenReturn(List.of(
                        instance("i1", "Leave request", "LeaveRequest", "row-1"),
                        instance("i2", "Expense claim", "ExpenseClaim", "row-2")));

        FlowApprovalTaskView first = view("i1");
        FlowApprovalTaskView second = view("i2");
        // Duplicate instance ids collapse into one lookup key.
        FlowApprovalTaskView third = view("i1");
        enricher.enrich(List.of(first, second, third));

        assertEquals("Leave request", first.getInstanceTitle());
        assertEquals("LeaveRequest", first.getModelName());
        assertEquals("row-1", first.getRowId());
        assertEquals(FlowExecutionStatus.WAITING, first.getInstanceStatus());
        assertEquals("Expense claim", second.getInstanceTitle());
        assertEquals("Leave request", third.getInstanceTitle());
    }

    @Test
    void aRecordsApprovalBringsWhatItKeptOfTheRecordAndWhoSubmittedIt() {
        FlowInstance instance = instance("i1", "Transfer · Alice", "EmpTransferRequest", "row-1");
        instance.setInitiatorId("hr-1");
        instance.setCreatedTime(LocalDateTime.of(2026, 10, 29, 9, 30));
        instance.setSubjectId(42L);
        instance.setDepartmentId(7L);
        instance.setEffectiveDate(LocalDate.of(2026, 11, 1));
        instance.setRecordCode("TR-0001");
        when(instanceService.findByInstanceIds(argThat(ids -> ids.contains("i1")))).thenReturn(List.of(instance));

        FlowApprovalTaskView view = view("i1");
        enricher.enrich(List.of(view));

        assertEquals("hr-1", view.getInitiator().getId());
        assertEquals(LocalDateTime.of(2026, 10, 29, 9, 30), view.getSubmittedTime());
        assertEquals("42", view.getSubject().getId());
        assertEquals(7L, view.getSubjectDept().getId());
        assertEquals(LocalDate.of(2026, 11, 1), view.getEffectiveDate());
        assertEquals("TR-0001", view.getRecordCode());
        verify(names).name(List.of(view.getInitiator()));
        verify(names).nameEmployees(List.of(view.getSubject()));
        verify(names).nameDepartments(List.of(view.getSubjectDept()));
    }

    private static CompiledFlowNode approvalNode(String nodeId, ApprovalNodeConfig cfg) {
        return CompiledFlowNode.builder().nodeId(nodeId).parsedConfig(cfg).build();
    }

    private static FlowApprovalTaskView pendingTask(String instanceId, String nodeId) {
        return FlowApprovalTaskView.builder().instanceId(instanceId).nodeId(nodeId)
                .status(FlowApprovalTaskStatus.PENDING).taskType(FlowApprovalTaskType.APPROVAL).build();
    }

    @Test
    void theStepsConfigurationSaysHowItsTaskMayBeActedOn() {
        FlowInstance instance = instance("i1", "Confirmation · Bob", "EmpConfirmationRequest", "row-1");
        instance.setBundleId(9L);
        CompiledFlowNode plain = approvalNode("plain", ApprovalNodeConfig.builder().returnEnabled(true).build());
        CompiledFlowNode required = approvalNode("required", ApprovalNodeConfig.builder()
                .formPermissions(Map.of("decision", FormFieldPermission.REQUIRED)).build());
        CompiledFlowNode editable = approvalNode("editable", ApprovalNodeConfig.builder()
                .formPermissions(Map.of("comment", FormFieldPermission.EDITABLE)).build());
        CompiledFlowDefinition definition = CompiledFlowDefinition.builder()
                .nodeIndex(Map.of("plain", plain, "required", required, "editable", editable))
                .build();
        when(instanceService.findByInstanceIds(argThat(ids -> ids.contains("i1")))).thenReturn(List.of(instance));
        when(bundleRegistry.getByBundleId(9L)).thenReturn(Optional.of(definition));

        FlowApprovalTaskView plainTask = pendingTask("i1", "plain");
        FlowApprovalTaskView requiredTask = pendingTask("i1", "required");
        FlowApprovalTaskView editableTask = pendingTask("i1", "editable");
        FlowApprovalTaskView blockedTask = pendingTask("i1", "plain");
        blockedTask.setBlocked(true);
        FlowApprovalTaskView doneTask = pendingTask("i1", "plain");
        doneTask.setStatus(FlowApprovalTaskStatus.APPROVED);
        enricher.enrich(List.of(plainTask, requiredTask, editableTask, blockedTask, doneTask));

        assertTrue(plainTask.getBatchable());
        assertTrue(plainTask.getReturnAllowed());
        assertFalse(requiredTask.getReturnAllowed());
        assertFalse(requiredTask.getBatchable(), "a batch cannot fill in a required field");
        assertTrue(requiredTask.getFillIn(), "a step with a required field is filled in and submitted");
        assertFalse(plainTask.getFillIn());
        assertTrue(editableTask.getBatchable(), "an editable field is left as it is");
        assertFalse(editableTask.getFillIn(), "an editable field may be left as it is: still approved");
        assertFalse(blockedTask.getBatchable(), "an add-sign prerequisite comes first");
        assertFalse(doneTask.getBatchable());
        // One lookup per revision on the page, however many tasks it carries.
        verify(bundleRegistry, times(1)).getByBundleId(9L);
    }

    @Test
    void aTaskOfAnInstanceNoLongerWaitingIsNotBatchable() {
        FlowInstance instance = instance("i1", "Leave", "LeaveRequest", "row-1");
        instance.setBundleId(9L);
        instance.setStatus(FlowExecutionStatus.COMPLETED);
        CompiledFlowDefinition definition = CompiledFlowDefinition.builder()
                .nodeIndex(Map.of("plain", approvalNode("plain", new ApprovalNodeConfig()))).build();
        when(instanceService.findByInstanceIds(argThat(ids -> ids.contains("i1")))).thenReturn(List.of(instance));
        when(bundleRegistry.getByBundleId(9L)).thenReturn(Optional.of(definition));

        FlowApprovalTaskView task = pendingTask("i1", "plain");
        enricher.enrich(List.of(task));

        assertFalse(task.getBatchable());
    }

    @Test
    void missingInstanceRowsLeaveViewsUntouched() {
        when(instanceService.findByInstanceIds(argThat(ids -> ids.contains("gone"))))
                .thenReturn(List.of());

        FlowApprovalTaskView view = view("gone");
        assertDoesNotThrow(() -> enricher.enrich(List.of(view)));
        assertNull(view.getInstanceTitle());
        assertNull(view.getInstanceStatus());
    }

    @Test
    void emptyViewsSkipTheLookupEntirely() {
        enricher.enrich(List.of());
        enricher.enrich(null);
        verifyNoInteractions(instanceService);
    }
}
