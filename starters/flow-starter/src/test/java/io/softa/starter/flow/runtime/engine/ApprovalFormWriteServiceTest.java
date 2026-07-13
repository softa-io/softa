package io.softa.starter.flow.runtime.engine;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Form-permission enforcement on approve: HIDDEN/READONLY edits are dropped,
 * a missing REQUIRED field blocks the transition, and only editable fields are written back to
 * the business row.
 */
class ApprovalFormWriteServiceTest {

    private ModelService<?> modelService;
    private ApprovalFormWriteService service;

    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        service = new ApprovalFormWriteService(modelService);
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
    void requiredFieldMissingBlocksTransitionAndDoesNotWrite() {
        CompiledFlowNode node = nodeWith(Map.of("reason", FormFieldPermission.REQUIRED));
        Map<String, Object> formData = new HashMap<>();
        formData.put("comment", "no reason supplied");

        assertThrows(FlowActionValidationException.class,
                () -> service.applyFormData(boundState(), node, formData));
        verifyNoInteractions(modelService);
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
