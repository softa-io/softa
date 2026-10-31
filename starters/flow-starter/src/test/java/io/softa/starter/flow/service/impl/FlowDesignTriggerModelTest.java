package io.softa.starter.flow.service.impl;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.dto.FlowDesignCreateRequest;
import io.softa.starter.flow.dto.FlowDesignSaveRequest;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.enums.FlowScenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * The model goes with the trigger: a flow's {@code modelName} column is the model its trigger names, kept on every
 * save and publish — cleared, too, when the trigger starts on no record. An Approval flow's trigger is a submission
 * of the document the flow was made for: the document is put back on every save, the condition the draft gives it
 * is kept.
 */
class FlowDesignTriggerModelTest {

    private final FlowDesignServiceImpl service = spy(new FlowDesignServiceImpl());
    @SuppressWarnings("unchecked")
    private final ModelService<String> rows = mock(ModelService.class);

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "modelService", rows);
        doReturn(true).when(service).updateOne(any(FlowDesign.class));
    }

    @Test
    void aFlowsModelIsTheOneItsTriggerNames() {
        doReturn("flow-1").when(service).createOne(any(FlowDesign.class));
        doReturn(Optional.empty()).when(service).getById("flow-1");

        FlowDesign created = service.createDesign(new FlowDesignCreateRequest("Welcome", FlowScenario.AUTOMATION, null,
                definition(new EntityChangeTrigger("Employee", List.of(), null))));

        assertEquals("Employee", created.getModelName());
        assertEquals(Boolean.TRUE, created.getEnabled(), "a new flow is on");
    }

    @Test
    void changingTheTriggerChangesTheModel() {
        doReturn(Optional.of(automation("Employee"))).when(service).getById("flow-1");

        service.saveDraft("flow-1", new FlowDesignSaveRequest(
                definition(new EntityChangeTrigger("Department", List.of(), null)), null, null, 1));
        assertEquals("Department", saved().getModelName());

        service.saveDraft("flow-1", new FlowDesignSaveRequest(definition(new ApiTrigger(null)), null, null, 2));
        // a trigger that starts on no record clears the model, which a patch alone cannot say
        verify(rows).updateOne(eq(FlowDesign.class.getSimpleName()), argThat(row -> row.containsKey("modelName")
                && row.get("modelName") == null && Integer.valueOf(2).equals(row.get("version"))));
    }

    @Test
    void anApprovalFlowKeepsItsDocumentWhateverItsDraftSays() {
        doReturn(Optional.of(approval("LeaveRequest"))).when(service).getById("flow-1");

        service.saveDraft("flow-1", new FlowDesignSaveRequest(
                definition(new SubmitTrigger("ExpenseClaim", "leaveRequestType == 'Leave'")), null, null, 1));

        FlowDesign saved = saved();
        assertEquals("LeaveRequest", saved.getModelName());
        assertEquals(new SubmitTrigger("LeaveRequest", "leaveRequestType == 'Leave'"),
                saved.getDesignJson().getTrigger(), "the document is put back, the condition kept");
    }

    @Test
    void aSeededApprovalFlowTakesItsDocumentFromItsTriggerWhenPublished() {
        FlowDesign seeded = approval(null);
        seeded.setDesignJson(definition(new SubmitTrigger("LeaveRequest")));
        doReturn(Optional.of(seeded)).when(service).getById("flow-1");

        service.upsertFromPublish("flow-1", 1);

        FlowDesign saved = saved();
        assertEquals("LeaveRequest", saved.getModelName());
        assertEquals(1, saved.getPublishedRevision());
        assertNull(saved.getDesignJson(), "publishing writes the markers, never the document");
    }

    private FlowDesign saved() {
        ArgumentCaptor<FlowDesign> captor = ArgumentCaptor.forClass(FlowDesign.class);
        verify(service, atLeastOnce()).updateOne(captor.capture());
        return captor.getValue();
    }

    private static DesignFlowDefinition definition(TriggerSource trigger) {
        return DesignFlowDefinition.builder().trigger(trigger).build();
    }

    private static FlowDesign automation(String modelName) {
        FlowDesign design = design(FlowScenario.AUTOMATION, modelName);
        design.setDesignJson(definition(new EntityChangeTrigger(modelName, List.of(), null)));
        return design;
    }

    private static FlowDesign approval(String document) {
        FlowDesign design = design(FlowScenario.APPROVAL, document);
        design.setDesignJson(definition(new SubmitTrigger(document)));
        return design;
    }

    private static FlowDesign design(FlowScenario scenario, String modelName) {
        FlowDesign design = new FlowDesign();
        design.setId("flow-1");
        design.setFlowName("Flow");
        design.setScenario(scenario);
        design.setModelName(modelName);
        design.setEnabled(true);
        design.setVersion(1);
        return design;
    }
}
