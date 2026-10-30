package io.softa.starter.flow.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.compiler.validation.ApprovableModelValidator;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.dto.ApprovalModelView;
import io.softa.starter.flow.dto.FlowDesignCreateRequest;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.LeaveRequest;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A document the application approves has one approval flow: a new one needs an approvable document without one,
 * an approval flow is not copied, a second is not enabled beside the first, and publishing refuses a document the
 * application cannot approve.
 */
class FlowApprovalDocumentTest {

    private static final String LEAVE = LeaveRequest.class.getSimpleName();

    private final ModelApprovalHandlerRegistry handlers = registryOf(LeaveRequest.class);
    private final FlowDesignServiceImpl service = spy(new FlowDesignServiceImpl());

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "approvalHandlers", handlers);
        doReturn(true).when(service).updateOne(any(FlowDesign.class), anyBoolean());
    }

    @Test
    void anApprovalFlowIsMadeForAnApprovableDocumentWithoutOne() {
        doReturn(List.of()).when(service).searchList(any(Filters.class));
        doReturn("flow-1").when(service).createOne(any(FlowDesign.class));
        doReturn(Optional.empty()).when(service).getById("flow-1");

        FlowDesign created = service.createDesign(new FlowDesignCreateRequest("Leave", FlowScenario.APPROVAL, LEAVE, null));

        assertEquals(LEAVE, created.getModelName());
        assertEquals(LEAVE, created.getDesignJson().getModelName(), "the document follows the row");
    }

    @Test
    void anApprovalFlowNeedsItsDocument() {
        assertThrows(FlowActionValidationException.class,
                () -> service.createDesign(new FlowDesignCreateRequest("Leave", FlowScenario.APPROVAL, " ", null)));
        verify(service, never()).createOne(any(FlowDesign.class));
    }

    @Test
    void aDocumentTheApplicationCannotApproveIsRefused() {
        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.createDesign(new FlowDesignCreateRequest("Claims", FlowScenario.APPROVAL, "ExpenseClaim", null)));

        assertTrue(refused.getMessage().contains("no approval handler"), refused.getMessage());
    }

    @Test
    void aDocumentWithAnApprovalFlowGetsNoSecond() {
        doReturn(List.of(design("flow-1", "Leave Approval", true))).when(service).searchList(any(Filters.class));

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.createDesign(new FlowDesignCreateRequest("Leave 2", FlowScenario.APPROVAL, LEAVE, null)));

        assertTrue(refused.getMessage().contains("Leave Approval"), refused.getMessage());
        verify(service, never()).createOne(any(FlowDesign.class));
    }

    @Test
    void anApprovalFlowIsNotCopied() {
        doReturn(Optional.of(design("flow-1", "Leave Approval", true))).when(service).getById("flow-1");

        assertThrows(FlowActionValidationException.class, () -> service.duplicateDesign("flow-1", null));
        verify(service, never()).createOne(any(FlowDesign.class));
    }

    @Test
    void aSecondApprovalFlowOfADocumentIsNotEnabledBesideTheFirst() {
        doReturn(Optional.of(design("old", "Old Leave Approval", false))).when(service).getById("old");
        doReturn(List.of(design("flow-1", "Leave Approval", true))).when(service).searchList(any(Filters.class));

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.enable("old"));

        assertTrue(refused.getMessage().contains("Leave Approval"), refused.getMessage());
        verify(service, never()).updateOne(any(FlowDesign.class), anyBoolean());
    }

    @Test
    void theApprovableDocumentsComeWithTheirFlow() {
        doReturn(List.of(design("flow-1", "Leave Approval", false))).when(service).searchList(any(Filters.class));

        List<ApprovalModelView> documents = service.listApprovalModels();

        assertEquals(1, documents.size());
        ApprovalModelView leave = documents.getFirst();
        assertEquals(LEAVE, leave.modelName());
        assertEquals("flow-1", leave.flowId());
        assertEquals(Boolean.FALSE, leave.flowEnabled());
    }

    @Test
    void publishingRefusesADocumentTheApplicationCannotApprove() {
        ApprovableModelValidator validator = new ApprovableModelValidator(handlers);

        assertTrue(validator.validate(approval(LEAVE), null).isEmpty());
        assertEquals("MODEL_NOT_APPROVABLE", validator.validate(approval("ExpenseClaim"), null).getFirst().code());
        assertTrue(validator.validate(DesignFlowDefinition.builder().scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null)).build(), null).isEmpty(), "only approval flows approve a document");
    }

    private static DesignFlowDefinition approval(String modelName) {
        return DesignFlowDefinition.builder().scenario(FlowScenario.APPROVAL).modelName(modelName)
                .trigger(new SubmitTrigger()).build();
    }

    private static FlowDesign design(String id, String name, boolean enabled) {
        FlowDesign design = new FlowDesign();
        design.setId(id);
        design.setFlowName(name);
        design.setScenario(FlowScenario.APPROVAL);
        design.setModelName(LEAVE);
        design.setEnabled(enabled);
        return design;
    }

    @SuppressWarnings("unchecked")
    private static ModelApprovalHandlerRegistry registryOf(Class<LeaveRequest> modelClass) {
        ModelApprovalHandler<LeaveRequest> handler = mock(ModelApprovalHandler.class);
        when(handler.modelClass()).thenReturn(modelClass);
        return ModelApprovalHandlerRegistry.of(List.of(handler));
    }

    @Test
    void nothingIsAskedOfAFlowThatApprovesNothing() {
        doReturn("flow-2").when(service).createOne(any(FlowDesign.class));
        doReturn(Optional.empty()).when(service).getById("flow-2");

        FlowDesign created = service.createDesign(new FlowDesignCreateRequest("Audit", FlowScenario.AUTOMATION, null, null));

        assertNull(created.getModelName());
    }
}
