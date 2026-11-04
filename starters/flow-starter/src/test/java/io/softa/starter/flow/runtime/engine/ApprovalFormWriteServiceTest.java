package io.softa.starter.flow.runtime.engine;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.LeaveRequest;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApprovalSubmission;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Form-permission enforcement on approve: only fields the node marks EDITABLE or REQUIRED are
 * written back to the business row — HIDDEN, READONLY and unlisted fields are dropped, and a node
 * without form permissions writes nothing — and a REQUIRED field the row will not hold once the edits
 * land blocks the transition. A record's approval then routes on the record as the edits left it.
 */
class ApprovalFormWriteServiceTest {

    private ModelService<Serializable> modelService;
    @SuppressWarnings("unchecked")
    private final ModelApprovalHandler<LeaveRequest> handler = mock(ModelApprovalHandler.class);
    private ApprovalFormWriteService service;
    private MockedStatic<IdUtils> idUtils;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        modelService = mock(ModelService.class);
        when(handler.modelClass()).thenReturn(LeaveRequest.class);
        service = new ApprovalFormWriteService(modelService, ModelApprovalHandlerRegistry.of(List.of(handler)));
        idUtils = mockStatic(IdUtils.class);
        idUtils.when(() -> IdUtils.formatId(anyString(), any(Serializable.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void tearDown() {
        idUtils.close();
    }

    private static CompiledFlowNode nodeWith(Map<String, FormFieldPermission> perms) {
        ApprovalNodeConfig cfg = new ApprovalNodeConfig();
        cfg.setFormPermissions(perms);
        CompiledFlowNode node = mock(CompiledFlowNode.class);
        when(node.getParsedConfig()).thenReturn(cfg);
        return node;
    }

    private static FlowExecutionState boundState() {
        FlowExecutionState state = new FlowExecutionState();
        state.setModelName("LeaveRequest");
        state.setRowId("7");
        return state;
    }

    @Test
    void writesOnlyEditableFieldsDroppingHiddenAndReadonly() {
        Map<String, FormFieldPermission> perms = new HashMap<>();
        perms.put("salary", FormFieldPermission.HIDDEN);
        perms.put("approvedBy", FormFieldPermission.READONLY);
        perms.put("comment", FormFieldPermission.EDITABLE);
        CompiledFlowNode node = nodeWith(perms);

        Map<String, Object> formData = new HashMap<>();
        formData.put("salary", 999999);
        formData.put("approvedBy", "attacker");
        formData.put("comment", "looks good");

        service.applyFormData(boundState(), node, formData);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateByFilter(eq("LeaveRequest"), any(Filters.class), captor.capture());
        assertEquals(Map.of("comment", "looks good"), captor.getValue());
    }

    @Test
    void unlistedFieldsAreDroppedWhileRequiredOnesAreWritten() {
        Map<String, FormFieldPermission> perms = new HashMap<>();
        perms.put("comment", FormFieldPermission.EDITABLE);
        perms.put("reason", FormFieldPermission.REQUIRED);
        CompiledFlowNode node = nodeWith(perms);

        Map<String, Object> formData = new HashMap<>();
        formData.put("comment", "looks good");
        formData.put("reason", "covered by policy");
        formData.put("days", 30);                   // not on the node's form
        formData.put("approvalStatus", "Approved"); // not on the node's form

        service.applyFormData(boundState(), node, formData);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateByFilter(eq("LeaveRequest"), any(Filters.class), captor.capture());
        assertEquals(Map.of("comment", "looks good", "reason", "covered by policy"), captor.getValue());
    }

    @Test
    void nodeWithoutFormPermissionsWritesNothing() {
        service.applyFormData(boundState(), nodeWith(null), Map.of("salary", 999999));
        service.applyFormData(boundState(), nodeWith(Map.of()), Map.of("salary", 999999));
        verifyNoInteractions(modelService);
    }

    @Test
    void noEditableFieldSubmittedWritesNothing() {
        CompiledFlowNode node = nodeWith(Map.of(
                "salary", FormFieldPermission.HIDDEN,
                "approvedBy", FormFieldPermission.READONLY,
                "comment", FormFieldPermission.EDITABLE));
        Map<String, Object> formData = new HashMap<>();
        formData.put("salary", 999999);
        formData.put("approvedBy", "attacker");
        formData.put("bonus", 5000);

        service.applyFormData(boundState(), node, formData);

        verifyNoInteractions(modelService);
    }

    @Test
    void requiredFieldMissingBlocksTransitionAndDoesNotWrite() {
        CompiledFlowNode node = nodeWith(Map.of("reason", FormFieldPermission.REQUIRED));
        Map<String, Object> formData = new HashMap<>();
        formData.put("comment", "no reason supplied");

        assertThrows(FlowActionValidationException.class,
                () -> service.applyFormData(boundState(), node, formData));
        verify(modelService, never()).updateByFilter(anyString(), any(Filters.class), anyMap());
    }

    @Test
    void aRequiredFieldIsCheckedOnTheRowAsTheEditsLeaveIt() {
        CompiledFlowNode node = nodeWith(Map.of("reason", FormFieldPermission.REQUIRED));
        when(modelService.getById(eq("LeaveRequest"), any(), eq(List.of("reason"))))
                .thenReturn(Optional.of(Map.of("id", 7L, "reason", "covered by policy")));

        // Already held: an approve without form data passes, and writes nothing.
        service.applyFormData(boundState(), node, null);
        verify(modelService, never()).updateByFilter(anyString(), any(Filters.class), anyMap());

        // Cleared by the edits: refused, however the row stands.
        assertThrows(FlowActionValidationException.class,
                () -> service.applyFormData(boundState(), node, Map.of("reason", " ")));
    }

    @Test
    void aRequiredFieldTheRowLacksBlocksAnApproveWithoutFormData() {
        CompiledFlowNode node = nodeWith(Map.of("reason", FormFieldPermission.REQUIRED));
        when(modelService.getById(eq("LeaveRequest"), any(), eq(List.of("reason"))))
                .thenReturn(Optional.of(Map.of("id", 7L)));

        assertThrows(FlowActionValidationException.class, () -> service.applyFormData(boundState(), node, Map.of()));
    }

    /**
     * A table holds a value while it keeps a row. Before any write, an empty one — stored, or sent whole — is refused
     * like a blank field (zingkey-hcm#2449).
     */
    @Test
    void anEmptyRequiredTableIsRefusedBeforeAnyWrite() {
        CompiledFlowNode node = nodeWith(Map.of("lineIds", FormFieldPermission.REQUIRED));
        when(modelService.getById(eq("LeaveRequest"), any(), eq(List.of("lineIds"))))
                .thenReturn(Optional.of(Map.of("id", 7L, "lineIds", List.of())));

        assertThrows(FlowActionValidationException.class, () -> service.applyFormData(boundState(), node, null));
        assertThrows(FlowActionValidationException.class,
                () -> service.applyFormData(boundState(), node, Map.of("lineIds", List.of())));
        verify(modelService, never()).updateByFilter(anyString(), any(Filters.class), anyMap());
    }

    @Test
    void editsOfARecordsApprovalRefreshTheVariablesItsFlowRoutesOn() {
        CompiledFlowNode node = nodeWith(Map.of("days", FormFieldPermission.EDITABLE));
        FlowExecutionState state = boundState();
        state.setRecordApproval(true);
        state.setVariables(new HashMap<>(Map.of("days", 3, "_subjectId", 5001L)));
        when(modelService.getById(eq("LeaveRequest"), any()))
                .thenReturn(Optional.of(Map.of("id", 7L, "days", 5)));
        when(handler.submission(any())).thenAnswer(invocation ->
                new ApprovalSubmission("Leave", Map.of("days", ((LeaveRequest) invocation.getArgument(0)).getDays())));

        service.applyFormData(state, node, Map.of("days", 5));

        assertEquals(Map.of("days", 5, "_subjectId", 5001L), state.getVariables());
    }

    /**
     * An approver's edits meet the model's own rules, as its page applies them on save: the handler sees the record as
     * the edits left it and the fields they wrote — after the write, before the flow routes on it (zingkey-hcm#2323).
     */
    @Test
    void anApproversEditsAreHeldToTheModelsOwnRules() {
        CompiledFlowNode node = nodeWith(Map.of("days", FormFieldPermission.EDITABLE));
        FlowExecutionState state = boundState();
        state.setRecordApproval(true);
        state.setVariables(new HashMap<>());
        when(modelService.getById(eq("LeaveRequest"), any())).thenReturn(Optional.of(Map.of("id", 7L, "days", 5)));
        when(handler.submission(any())).thenReturn(new ApprovalSubmission("Leave", Map.of()));

        service.applyFormData(state, node, Map.of("days", 5));

        InOrder order = inOrder(modelService, handler);
        order.verify(modelService).updateByFilter(eq("LeaveRequest"), any(Filters.class), anyMap());
        order.verify(handler).afterApproverEdit(argThat(record -> record.getDays() == 5), eq(Set.of("days")));
        order.verify(handler).submission(any());
    }

    @Test
    void theModelRefusingTheEditsRefusesTheApprove() {
        CompiledFlowNode node = nodeWith(Map.of("days", FormFieldPermission.EDITABLE));
        FlowExecutionState state = boundState();
        state.setRecordApproval(true);
        when(modelService.getById(eq("LeaveRequest"), any())).thenReturn(Optional.of(Map.of("id", 7L, "days", 50)));
        doThrow(new IllegalStateException("Not that many days")).when(handler).afterApproverEdit(any(), any());

        assertThrows(IllegalStateException.class, () -> service.applyFormData(state, node, Map.of("days", 50)));
        verify(handler, never()).submission(any());
    }

    @Test
    void noBusinessRowBoundSkipsWrite() {
        CompiledFlowNode node = nodeWith(Map.of("comment", FormFieldPermission.EDITABLE));
        FlowExecutionState unbound = new FlowExecutionState(); // no modelName / rowId
        service.applyFormData(unbound, node, Map.of("comment", "x"));
        verifyNoInteractions(modelService);
    }

    @Test
    void nullOrEmptyFormDataIsNoOp() {
        CompiledFlowNode node = nodeWith(Map.of("comment", FormFieldPermission.EDITABLE));
        service.applyFormData(boundState(), node, null);
        service.applyFormData(boundState(), node, Map.of());
        verifyNoInteractions(modelService);
    }
}
