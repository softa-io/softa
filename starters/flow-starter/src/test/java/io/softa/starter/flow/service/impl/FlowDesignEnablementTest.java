package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.design.trigger.SubflowTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.dto.FlowDesignStatusView;
import io.softa.starter.flow.entity.FlowBundle;
import io.softa.starter.flow.entity.FlowDesign;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.StubFlowBundleRegistry;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.service.FlowBundleService;
import io.softa.starter.flow.service.FlowInstanceService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Switching a flow off and on writes its switch alone, only when it changes. A flow stays on while any of its
 * instances is unfinished, and while a flow that may still run it as a subflow calls it — an enabled flow's
 * effective revision, or the revision an unfinished instance runs on. A Compute flow is not switched on beside
 * another computing the same output of its model, nor an approval flow beside one taking the same records.
 */
class FlowDesignEnablementTest {

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService =
            new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final FlowDesignServiceImpl service = spy(new FlowDesignServiceImpl());

    private final FlowInstanceService instances = mock(FlowInstanceService.class);

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "bundleRegistry", bundleRegistry);
        ReflectionTestUtils.setField(service, "instanceService", instances);
        when(instances.unfinishedBundleIds()).thenReturn(Set.of());
        doReturn(true).when(service).updateOne(any(FlowDesign.class));
    }

    @Test
    void disablingSwitchesTheFlowOff() {
        doReturn(Optional.of(design("audit", true))).when(service).getById("audit");

        FlowDesignStatusView status = service.disable("audit");

        assertFalse(status.enabled());
        verify(service).updateOne(argThat((FlowDesign patch) -> Boolean.FALSE.equals(patch.getEnabled())
                && patch.getDesignJson() == null));
    }

    @Test
    void enablingSwitchesItBackOn() {
        doReturn(Optional.of(design("audit", false))).when(service).getById("audit");

        assertTrue(service.enable("audit").enabled());
        verify(service).updateOne(argThat((FlowDesign patch) -> Boolean.TRUE.equals(patch.getEnabled())));
    }

    @Test
    void switchingToTheStateItIsInWritesNothing() {
        doReturn(Optional.of(design("audit", true))).when(service).getById("audit");

        assertTrue(service.enable("audit").enabled());
        verify(service, never()).updateOne(any(FlowDesign.class));
    }

    @Test
    void aFlowAnEnabledFlowRunsAsASubflowStaysOn() {
        publishService.publish("child", flow("Child", new SubflowTrigger()));
        publishService.publish("parent", flow("Parent", new ApiTrigger(null), subflowNode("child")));
        doReturn(Optional.of(design("child", true))).when(service).getById("child");

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.disable("child"));

        assertTrue(refused.getMessage().contains("Parent"), refused.getMessage());
        verify(service, never()).updateOne(any(FlowDesign.class));
    }

    @Test
    void aDisabledCallerDoesNotHoldItsSubflowOn() {
        publishService.publish("child", flow("Child", new SubflowTrigger()));
        publishService.publish("parent", flow("Parent", new ApiTrigger(null), subflowNode("child")));
        bundleRegistry.disable("parent");
        doReturn(Optional.of(design("child", true))).when(service).getById("child");

        assertFalse(service.disable("child").enabled());
    }

    @Test
    void aFlowWithUnfinishedInstancesStaysOn() {
        doReturn(Optional.of(design("leave", true))).when(service).getById("leave");
        when(instances.countUnfinished("leave")).thenReturn(3L);

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.disable("leave"));

        assertTrue(refused.getMessage().contains("3 of its instances"), refused.getMessage());
        verify(service, never()).updateOne(any(FlowDesign.class));
    }

    @Test
    void anUnfinishedInstanceOfAnEarlierRevisionHoldsItsSubflowOn() {
        publishService.publish("child", flow("Child", new SubflowTrigger()));
        CompiledFlowDefinition earlier = publishService.publish("parent",
                flow("Parent", new ApiTrigger(null), subflowNode("child")));
        publishService.publish("parent", flow("Parent", new ApiTrigger(null)));
        when(instances.unfinishedBundleIds()).thenReturn(Set.of(earlier.getBundleId()));
        doReturn(Optional.of(design("child", true))).when(service).getById("child");

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.disable("child"));

        assertTrue(refused.getMessage().contains("Parent"), refused.getMessage());
    }

    @Test
    void anApprovalFlowIsNotEnabledBesideOneTakingTheSameRecords() {
        CompiledFlowDefinition dormant = publishService.publish("leave-sg",
                approval("Leave Approval SG", "leaveRequestType == 'Leave'"));
        bundleRegistry.disable("leave-sg");
        publishService.publish("leave", approval("Leave Approval", "leaveRequestType == 'Leave'"));
        FlowDesign design = design("leave-sg", false);
        design.setScenario(FlowScenario.APPROVAL);
        doReturn(Optional.of(design)).when(service).getById("leave-sg");
        FlowBundleService bundles = mock(FlowBundleService.class);
        FlowBundle active = new FlowBundle();
        active.setFlowId("leave-sg");
        active.setCompiledJson(JsonUtils.objectToString(dormant));
        when(bundles.findActiveByFlowId("leave-sg")).thenReturn(Optional.of(active));
        ReflectionTestUtils.setField(service, "bundleService", bundles);

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.enable("leave-sg"));

        assertTrue(refused.getMessage().contains("Leave Approval"), refused.getMessage());
        verify(service, never()).updateOne(any(FlowDesign.class));
    }

    @Test
    void anApprovalFlowWithAConditionOfItsOwnIsPublishedBesideTheOthers() {
        publishService.publish("leave", approval("Leave Approval", "leaveRequestType == 'Leave'"));

        assertThrows(FlowActionValidationException.class, () -> publishService.publish("copy",
                approval("Leave Approval (copy)", "leaveRequestType == 'Leave'")), "a copy left as it was");
        publishService.publish("cancel", approval("Leave Cancellation Approval", "leaveRequestType == 'CancelLeave'"));
    }

    @Test
    void aComputeFlowIsNotEnabledBesideOneComputingTheSameOutput() {
        CompiledFlowDefinition dormant = publishService.publish("total-2",
                compute("Order Total 2", "totalAmount"));
        bundleRegistry.disable("total-2");
        publishService.publish("total", compute("Order Total", "totalAmount"));
        FlowDesign design = design("total-2", false);
        design.setScenario(FlowScenario.COMPUTE);
        doReturn(Optional.of(design)).when(service).getById("total-2");
        FlowBundleService bundles = mock(FlowBundleService.class);
        FlowBundle active = new FlowBundle();
        active.setFlowId("total-2");
        active.setCompiledJson(JsonUtils.objectToString(dormant));
        when(bundles.findActiveByFlowId("total-2")).thenReturn(Optional.of(active));
        ReflectionTestUtils.setField(service, "bundleService", bundles);

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.enable("total-2"));

        assertTrue(refused.getMessage().contains("totalAmount"), refused.getMessage());
        verify(service, never()).updateOne(any(FlowDesign.class));
    }

    /** start → a script computing {@code output} → end, a Compute flow of Order. */
    private static DesignFlowDefinition compute(String name, String output) {
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(FlowScenario.COMPUTE)
                .trigger(new FieldChangeTrigger("Order", List.of()))
                .declaredOutputs(List.of(output))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("calc").type(FlowNodeType.SCRIPT).label("calc")
                                        .config(Map.of("expression", "1", "outputVariable", output)).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("calc").build(),
                                FlowGraphEdge.builder().id("e2").source("calc").target("end").build()))
                        .build())
                .build();
    }

    /** start → an approval → end, an Approval flow of leave requests taking those {@code condition} holds for. */
    private static DesignFlowDefinition approval(String name, String condition) {
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(FlowScenario.APPROVAL)
                .trigger(new SubmitTrigger("LeaveRequest", condition))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("approval").type(FlowNodeType.APPROVAL).label("Manager")
                                        .config(Map.of("approvers", List.of("manager"))).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("approval").build(),
                                FlowGraphEdge.builder().id("e2").source("approval").target("end").build()))
                        .build())
                .build();
    }

    private static FlowDesign design(String id, boolean enabled) {
        FlowDesign design = new FlowDesign();
        design.setId(id);
        design.setFlowName(id);
        design.setEnabled(enabled);
        return design;
    }

    private static FlowGraphNode subflowNode(String childFlowId) {
        return FlowGraphNode.builder().id("subflow").type(FlowNodeType.SUBFLOW).label("Child")
                .config(Map.of("subflowId", childFlowId)).build();
    }

    /** start → {@code middle} (when given) → end, an Automation flow. */
    private static DesignFlowDefinition flow(String name, TriggerSource trigger, FlowGraphNode... middle) {
        List<FlowGraphNode> nodes = new ArrayList<>();
        List<FlowGraphEdge> edges = new ArrayList<>();
        nodes.add(FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build());
        String previous = "start";
        for (FlowGraphNode node : middle) {
            nodes.add(node);
            edges.add(FlowGraphEdge.builder().id("to-" + node.getId()).source(previous).target(node.getId()).build());
            previous = node.getId();
        }
        nodes.add(FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build());
        edges.add(FlowGraphEdge.builder().id("to-end").source(previous).target("end").build());
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(FlowScenario.AUTOMATION)
                .trigger(trigger)
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }
}
