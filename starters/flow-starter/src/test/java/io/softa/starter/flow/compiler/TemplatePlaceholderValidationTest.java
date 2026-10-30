package io.softa.starter.flow.compiler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@code {{ }}} placeholder a node's settings hold is interpolated as the node runs, so each must run: an
 * expression that compiles, a variable, a reserved field reference — closed.
 */
class TemplatePlaceholderValidationTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void placeholdersThatRunPublish() {
        assertEquals(List.of(), codes(Map.of(
                "subject", "Claim {{ claimNo }} of {{ applicant.name }}",
                "textBody", "Total: {{ amount * 2 }}",
                "templateVariables", Map.of("owner", "{{ @createdId }}"),
                "to", List.of("{{ applicantEmail }}", "ops@example.com"))));
    }

    @Test
    void aPlaceholderThatDoesNotCompileIsRefusedWhereverItIs() {
        List<CompileDiagnostic> diagnostics = validate(Map.of(
                "to", List.of("ops@example.com", "{{ amount + }}")));

        assertEquals(List.of("INVALID_TEMPLATE_PLACEHOLDER"), diagnostics.stream().map(CompileDiagnostic::code).toList());
        String message = diagnostics.getFirst().message();
        assertTrue(message.contains("Node 'Notify' (to[1])") && message.contains("{{ amount + }}"), message);
    }

    @Test
    void anUnclosedPlaceholderIsRefused() {
        assertTrue(validate(Map.of("subject", "Claim {{ claimNo"))
                .getFirst().message().contains("is not closed"));
    }

    @Test
    void anEmptyPlaceholderOrABadReferenceIsRefused() {
        assertEquals(List.of("INVALID_TEMPLATE_PLACEHOLDER"), codes(Map.of("subject", "Claim {{   }}")));
        assertEquals(List.of("INVALID_TEMPLATE_PLACEHOLDER"), codes(Map.of("subject", "Claim {{ @1x }}")));
    }

    private List<String> codes(Map<String, Object> input) {
        return validate(input).stream().map(CompileDiagnostic::code).toList();
    }

    /** The settings of a node that holds `input`'s entries beside its own. */
    private List<CompileDiagnostic> validate(Map<String, Object> input) {
        Map<String, Object> config = new LinkedHashMap<>(input);
        config.put("expression", "1");
        config.put("outputVariable", "x");
        return compiler.validate(DesignFlowDefinition.builder()
                .name("Claims")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("notify").type(FlowNodeType.SCRIPT).label("Notify")
                                        .config(config)
                                        .build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("notify").build(),
                                FlowGraphEdge.builder().id("e2").source("notify").target("end").build()))
                        .build())
                .build());
    }
}
