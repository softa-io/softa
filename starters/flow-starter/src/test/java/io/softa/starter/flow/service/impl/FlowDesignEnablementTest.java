package io.softa.starter.flow.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Switching a flow off and on writes its switch only when it changes, and keeps on a flow an enabled flow runs as
 * a subflow — switching it off would fail every run of its caller. A Compute flow is not switched on beside another
 * computing the same output of its model.
 */
class FlowDesignEnablementTest {

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService =
            new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);
    private final FlowDesignServiceImpl service = spy(new FlowDesignServiceImpl());

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(service, "bundleRegistry", bundleRegistry);
        doReturn(true).when(service).updateOne(any(FlowDesign.class), anyBoolean());
    }

    @Test
    void disablingSwitchesTheFlowOff() {
        doReturn(Optional.of(design("audit", true))).when(service).getById("audit");

        FlowDesignStatusView status = service.disable("audit");

        assertFalse(status.enabled());
        verify(service).updateOne(argThat(design -> Boolean.FALSE.equals(design.getEnabled())), anyBoolean());
    }

    @Test
    void enablingSwitchesItBackOn() {
        doReturn(Optional.of(design("audit", false))).when(service).getById("audit");

        assertTrue(service.enable("audit").enabled());
        verify(service).updateOne(argThat(design -> Boolean.TRUE.equals(design.getEnabled())), anyBoolean());
    }

    @Test
    void switchingToTheStateItIsInWritesNothing() {
        doReturn(Optional.of(design("audit", true))).when(service).getById("audit");

        assertTrue(service.enable("audit").enabled());
        verify(service, never()).updateOne(any(FlowDesign.class), anyBoolean());
    }

    @Test
    void aFlowAnEnabledFlowRunsAsASubflowStaysOn() {
        publishService.publish("child", flow("Child", new SubflowTrigger()));
        publishService.publish("parent", flow("Parent", new ApiTrigger(null), subflowNode("child")));
        doReturn(Optional.of(design("child", true))).when(service).getById("child");

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> service.disable("child"));

        assertTrue(refused.getMessage().contains("Parent"), refused.getMessage());
        verify(service, never()).updateOne(any(FlowDesign.class), anyBoolean());
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
        verify(service, never()).updateOne(any(FlowDesign.class), anyBoolean());
    }

    /** start → a script computing {@code output} → end, a Compute flow of Order. */
    private static DesignFlowDefinition compute(String name, String output) {
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(FlowScenario.COMPUTE)
                .modelName("Order").trigger(new FieldChangeTrigger(List.of()))
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
