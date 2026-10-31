package io.softa.starter.flow.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.compiler.DefaultFlowCompiler;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.service.impl.FlowPublishServiceImpl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Compute flows of a model answer one form change together, so an output has one author among the enabled
 * ones: a second flow declaring it is refused as it is published, or rolled back to a revision that declares it.
 */
class ComputeOutputAuthorshipTest {

    private final StubFlowBundleRegistry bundleRegistry = new StubFlowBundleRegistry();
    private final FlowPublishServiceImpl publishService =
            new FlowPublishServiceImpl(new DefaultFlowCompiler(), bundleRegistry);

    @Test
    void aSecondFlowComputingTheSameOutputOfAModelIsRefused() {
        publishService.publish("total", compute("Order Total", "Order", "totalAmount"));

        FlowActionValidationException refused = assertThrows(FlowActionValidationException.class,
                () -> publishService.publish("total-2", compute("Order Total 2", "Order", "totalAmount", "tax")));

        assertTrue(refused.getMessage().contains("totalAmount") && refused.getMessage().contains("Order Total"),
                refused.getMessage());
        assertTrue(bundleRegistry.getActiveByFlowId("total-2").isEmpty(), "nothing was registered");
    }

    @Test
    void theSameOutputOfAnotherModelOrOfADisabledFlowIsNoClash() {
        publishService.publish("total", compute("Order Total", "Order", "totalAmount"));
        assertDoesNotThrow(() -> publishService.publish("quote-total", compute("Quote Total", "Quote", "totalAmount")));

        bundleRegistry.disable("total");
        assertDoesNotThrow(() -> publishService.publish("total-2", compute("Order Total 2", "Order", "totalAmount")));
    }

    @Test
    void aFlowRepublishedKeepsItsOwnOutputs() {
        publishService.publish("total", compute("Order Total", "Order", "totalAmount"));

        assertDoesNotThrow(() -> publishService.publish("total", compute("Order Total", "Order", "totalAmount", "tax")));
    }

    @Test
    void rollingBackToARevisionThatClashesIsRefused() {
        CompiledFlowDefinition first = publishService.publish("total", compute("Order Total", "Order", "totalAmount"));
        publishService.publish("total", compute("Order Total", "Order", "tax"));
        publishService.publish("grand", compute("Grand Total", "Order", "totalAmount"));

        assertThrows(FlowActionValidationException.class, () -> publishService.activateBundle(first.getBundleId()));
    }

    private static DesignFlowDefinition compute(String name, String model, String... outputs) {
        List<FlowGraphNode> nodes = new ArrayList<>();
        List<FlowGraphEdge> edges = new ArrayList<>();
        nodes.add(FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build());
        String previous = "start";
        for (String output : outputs) {
            nodes.add(FlowGraphNode.builder().id(output).type(FlowNodeType.SCRIPT).label(output)
                    .config(Map.of("expression", "1", "outputVariable", output)).build());
            edges.add(FlowGraphEdge.builder().id(previous + "-" + output).source(previous).target(output).build());
            previous = output;
        }
        nodes.add(FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build());
        edges.add(FlowGraphEdge.builder().id(previous + "-end").source(previous).target("end").build());
        return DesignFlowDefinition.builder()
                .name(name)
                .scenario(FlowScenario.COMPUTE)
                .modelName(model).trigger(new FieldChangeTrigger(List.of()))
                .declaredOutputs(List.of(outputs))
                .graph(FlowGraphDocument.builder().nodes(nodes).edges(edges).build())
                .build();
    }
}
