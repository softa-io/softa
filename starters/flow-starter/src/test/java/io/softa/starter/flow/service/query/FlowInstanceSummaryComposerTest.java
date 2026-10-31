package io.softa.starter.flow.service.query;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.dto.FlowInstanceSummaryView;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.dto.FlowUrgeStatus;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.RecordEffectStatus;
import io.softa.starter.flow.runtime.LeaveRequest;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;
import io.softa.starter.flow.runtime.engine.UrgeThrottle;
import io.softa.starter.flow.runtime.engine.WithdrawPolicyGuard;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;
import io.softa.starter.flow.service.support.FlowUserNames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link FlowInstanceSummaryComposer}: the steps a waiting instance waits on, from its pending
 * approval tasks; whether a returned approval still waits for its record; what a record's approval kept.
 */
class FlowInstanceSummaryComposerTest {

    @SuppressWarnings("unchecked")
    private final ModelService<Serializable> modelService = mock(ModelService.class);
    private final FlowUserNames names = mock(FlowUserNames.class);
    private final FlowInstanceStore instanceStore = mock(FlowInstanceStore.class);
    private final WithdrawPolicyGuard withdrawPolicyGuard = mock(WithdrawPolicyGuard.class);
    private final UrgeThrottle urgeThrottle = mock(UrgeThrottle.class);
    private final FlowInstanceSummaryComposer composer = new FlowInstanceSummaryComposer(modelService, names,
            instanceStore, withdrawPolicyGuard, urgeThrottle, ModelApprovalHandlerRegistry.of(List.of()));

    private static FlowInstance instance(String instanceId, FlowExecutionStatus status) {
        FlowInstance instance = new FlowInstance();
        instance.setInstanceId(instanceId);
        instance.setStatus(status);
        instance.setRecordApproval(true);
        instance.setInitiatorId("u-1");
        return instance;
    }

    private static Map<String, Object> task(String instanceId, String nodeId, String actorId) {
        return Map.of("instanceId", instanceId, "nodeId", nodeId, "nodeLabel", nodeId + " label", "actorId", actorId);
    }

    @Test
    void aWaitingInstanceShowsItsStepsWithTheApproversStillToAct() {
        when(modelService.searchList(eq("FlowApprovalTask"), any(FlexQuery.class))).thenReturn(List.of(
                task("i1", "manager", "m-1"),
                task("i1", "manager", "m-2"),
                task("i1", "hr", "h-1")));

        FlowInstanceSummaryView view = composer.summarize(List.of(instance("i1", FlowExecutionStatus.WAITING)), null).getFirst();

        List<FlowRecordApprovalsView.PendingStep> steps = view.getCurrentSteps();
        assertEquals(List.of("manager", "hr"), steps.stream().map(FlowRecordApprovalsView.PendingStep::getNodeId).toList());
        assertEquals(List.of("m-1", "m-2"),
                steps.getFirst().getApprovers().stream().map(FlowRecordApprovalsView.Actor::getId).toList());
        assertEquals("manager label", steps.getFirst().getNodeLabel());
        assertEquals("u-1", view.getInitiator().getId());
    }

    @Test
    void anInstanceThatWaitsOnNobodyReadsNoTasks() {
        FlowInstanceSummaryView view = composer.summarize(List.of(instance("i1", FlowExecutionStatus.COMPLETED)), null).getFirst();

        assertTrue(view.getCurrentSteps().isEmpty());
        verify(modelService, never()).searchList(eq("FlowApprovalTask"), any(FlexQuery.class));
    }

    @Test
    void aReturnedApprovalWaitsForItsRecordUntilALaterSubmissionReplacesIt() {
        FlowInstance waiting = instance("i1", FlowExecutionStatus.RETURNED);
        FlowInstance replaced = instance("i2", FlowExecutionStatus.RETURNED);
        replaced.setSuperseded(true);

        List<FlowInstanceSummaryView> views = composer.summarize(List.of(waiting, replaced), null);

        assertTrue(views.get(0).getAwaitingRevision());
        assertFalse(views.get(1).getAwaitingRevision());
    }

    @Test
    void aRecordsApprovalShowsWhatItKeptOfTheRecord() {
        FlowInstance instance = instance("i1", FlowExecutionStatus.COMPLETED);
        instance.setSubjectId(42L);
        instance.setDepartmentId(7L);
        instance.setEffectiveDate(LocalDate.of(2026, 11, 1));
        instance.setRecordCode("TR-0001");

        FlowInstanceSummaryView view = composer.summarize(List.of(instance), null).getFirst();

        assertEquals("42", view.getSubject().getId());
        assertEquals(7L, view.getSubjectDept().getId());
        assertEquals(LocalDate.of(2026, 11, 1), view.getEffectiveDate());
        assertEquals("TR-0001", view.getRecordCode());
        verify(names).nameEmployees(List.of(view.getSubject()));
        verify(names).nameDepartments(List.of(view.getSubjectDept()));
    }

    @Test
    void theRequestersOwnWaitingApprovalSaysWhetherTheyMayWithdrawOrUrgeItNow() {
        FlowInstance own = instance("i1", FlowExecutionStatus.WAITING);
        own.setScenario(FlowScenario.APPROVAL);
        FlowInstance someoneElses = instance("i2", FlowExecutionStatus.WAITING);
        someoneElses.setScenario(FlowScenario.APPROVAL);
        someoneElses.setInitiatorId("u-2");
        FlowInstance ended = instance("i3", FlowExecutionStatus.COMPLETED);
        ended.setScenario(FlowScenario.APPROVAL);
        FlowExecutionState state = FlowExecutionState.builder().instanceId("i1").build();
        when(instanceStore.listByInstanceIds(argThat(ids -> ids.size() == 1 && ids.contains("i1"))))
                .thenReturn(List.of(state));
        when(withdrawPolicyGuard.withdrawable(state, "u-1")).thenReturn(true);
        FlowUrgeStatus urged = new FlowUrgeStatus(false, LocalDateTime.of(2026, 10, 29, 18, 0));
        when(urgeThrottle.status(eq(state), eq("u-1"), any())).thenReturn(urged);

        List<FlowInstanceSummaryView> views = composer.summarize(List.of(own, someoneElses, ended), "u-1");

        assertTrue(views.get(0).getWithdrawable());
        assertEquals(urged, views.get(0).getUrge());
        assertNull(views.get(1).getWithdrawable(), "not theirs to withdraw");
        assertNull(views.get(2).getUrge(), "an approval that ended takes no action");
    }

    @Test
    void theMonitoringConsoleSaysWhetherAnAdministratorMayUrgeEachWaitingApproval() {
        FlowInstance waiting = instance("i1", FlowExecutionStatus.WAITING);
        waiting.setInitiatorId("u-2");
        FlowInstance ended = instance("i2", FlowExecutionStatus.COMPLETED);
        FlowExecutionState state = FlowExecutionState.builder().instanceId("i1").build();
        when(instanceStore.listByInstanceIds(argThat(ids -> ids.size() == 1 && ids.contains("i1"))))
                .thenReturn(List.of(state));
        FlowUrgeStatus urgeable = new FlowUrgeStatus(true, null);
        when(urgeThrottle.status(eq(state), any(LocalDateTime.class))).thenReturn(urgeable);
        Page<FlowInstance> page = Page.of(1, 50);
        page.setRows(List.of(waiting, ended));

        List<FlowInstanceSummaryView> views = composer.summarizeForAdministrator(page, "admin-1").getRows();

        assertEquals(urgeable, views.get(0).getUrge(), "someone else's approval, urged as an administrator");
        assertNull(views.get(1).getUrge(), "an approval that ended takes no action");
        verify(urgeThrottle, never()).status(any(), eq("admin-1"), any());
    }

    @Test
    void aRecordsApprovalWhoseRecordIsGoneSaysSo() {
        FlowInstance kept = instance("i1", FlowExecutionStatus.RETURNED);
        kept.setModelName("LeaveRequest");
        kept.setRowId("10");
        FlowInstance deleted = instance("i2", FlowExecutionStatus.RETURNED);
        deleted.setModelName("LeaveRequest");
        deleted.setRowId("11");
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<IdUtils> idUtils = mockStatic(IdUtils.class)) {
            models.when(() -> ModelManager.existModel("LeaveRequest")).thenReturn(true);
            idUtils.when(() -> IdUtils.formatIds(anyString(), anyList()))
                    .thenAnswer(invocation -> ((List<?>) invocation.getArgument(1)).stream()
                            .map(id -> Long.valueOf(id.toString())).toList());
            when(modelService.getByIds(eq("LeaveRequest"), eq(List.of(10L, 11L)), any()))
                    .thenReturn(List.of(Map.of("id", 10L)));

            List<FlowInstanceSummaryView> views = composer.summarize(List.of(kept, deleted), null);

            assertFalse(views.get(0).getRecordDeleted());
            assertTrue(views.get(1).getRecordDeleted());
        }
    }

    @Test
    void aRecordsApprovalWhoseRecordWasCancelledSaysSo() {
        // A returned overtime request its applicant then cancelled, beside one they are still to revise.
        FlowInstance cancelled = instance("i1", FlowExecutionStatus.RETURNED);
        cancelled.setModelName("OvertimeRequest");
        cancelled.setRowId("10");
        FlowInstance toRevise = instance("i2", FlowExecutionStatus.RETURNED);
        toRevise.setModelName("OvertimeRequest");
        toRevise.setRowId("11");
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class);
             MockedStatic<IdUtils> idUtils = mockStatic(IdUtils.class)) {
            models.when(() -> ModelManager.existModel("OvertimeRequest")).thenReturn(true);
            models.when(() -> ModelManager.existField("OvertimeRequest", "approvalStatus")).thenReturn(true);
            idUtils.when(() -> IdUtils.formatIds(anyString(), anyList()))
                    .thenAnswer(invocation -> ((List<?>) invocation.getArgument(1)).stream()
                            .map(id -> Long.valueOf(id.toString())).toList());
            when(modelService.getByIds(eq("OvertimeRequest"), eq(List.of(10L, 11L)), eq(List.of("id", "approvalStatus"))))
                    .thenReturn(List.of(Map.of("id", 10L, "approvalStatus", "Cancelled"),
                            Map.of("id", 11L, "approvalStatus", "Draft")));

            List<FlowInstanceSummaryView> views = composer.summarize(List.of(cancelled, toRevise), null);

            assertTrue(views.get(0).getRecordCancelled());
            assertFalse(views.get(0).getRecordDeleted());
            assertFalse(views.get(1).getRecordCancelled());
        }
    }

    @Test
    void anApprovedRecordShowsWhereItsChangeStandsAsItsHandlerReadsIt() {
        @SuppressWarnings("unchecked")
        ModelApprovalHandler<LeaveRequest> handler = mock(ModelApprovalHandler.class);
        when(handler.modelClass()).thenReturn(LeaveRequest.class);
        when(handler.effectStatuses(argThat(ids -> ids.containsAll(List.of("10", "11")))))
                .thenReturn(Map.of("10", RecordEffectStatus.APPLIED, "11", RecordEffectStatus.FAILED));
        FlowInstanceSummaryComposer withHandler = new FlowInstanceSummaryComposer(modelService, names, instanceStore,
                withdrawPolicyGuard, urgeThrottle, ModelApprovalHandlerRegistry.of(List.of(handler)));
        FlowInstance applied = instance("i1", FlowExecutionStatus.COMPLETED);
        applied.setModelName("LeaveRequest");
        applied.setRowId("10");
        FlowInstance failed = instance("i2", FlowExecutionStatus.COMPLETED);
        failed.setModelName("LeaveRequest");
        failed.setRowId("11");
        FlowInstance waiting = instance("i3", FlowExecutionStatus.WAITING);
        waiting.setModelName("LeaveRequest");
        waiting.setRowId("12");
        try (MockedStatic<ModelManager> models = mockStatic(ModelManager.class)) {
            // The record check is not what this test is about.
            models.when(() -> ModelManager.existModel("LeaveRequest")).thenReturn(false);

            List<FlowInstanceSummaryView> views = withHandler.summarize(List.of(applied, failed, waiting), null);

            assertEquals(RecordEffectStatus.APPLIED, views.get(0).getEffectStatus());
            assertEquals(RecordEffectStatus.FAILED, views.get(1).getEffectStatus());
            assertNull(views.get(2).getEffectStatus(), "an approval still running has no change to apply yet");
        }
    }
}
