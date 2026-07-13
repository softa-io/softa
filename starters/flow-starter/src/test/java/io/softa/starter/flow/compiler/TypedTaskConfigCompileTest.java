package io.softa.starter.flow.compiler;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.FlowCompileException;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compile-time shape validation for every typed task config: a task node missing a
 * required input key must fail at publish with MISSING_REQUIRED_INPUT on that field,
 * not at execution time.
 */
class TypedTaskConfigCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    private record RejectionCase(FlowNodeType nodeType, Map<String, Object> partialInput, String expectedField) {}

    private static final List<RejectionCase> CASES = List.of(
            new RejectionCase(FlowNodeType.SEND_SMS,
                    Map.of("content", "hi"), "config.input.phoneNumbers"),
            // phoneNumbers present but neither content nor templateCode
            new RejectionCase(FlowNodeType.SEND_SMS,
                    Map.of("phoneNumbers", List.of("13800000000")), "config.input.content"),
            new RejectionCase(FlowNodeType.SEND_EMAIL,
                    Map.of("subject", "hello"), "config.input.to"),
            new RejectionCase(FlowNodeType.SEND_INBOX_NOTIFICATION,
                    Map.of("title", "t", "content", "c"), "config.input.recipientIds"),
            new RejectionCase(FlowNodeType.GENERATE_FILE,
                    Map.of("rowId", "1"), "config.input.templateId"),
            new RejectionCase(FlowNodeType.VALIDATE_DATA,
                    Map.of("expression", "amount > 0"), "config.input.exceptionMsg"),
            new RejectionCase(FlowNodeType.TRANSFORM,
                    Map.of("itemKey", "deptId"), "config.input.collectionVariable"),
            new RejectionCase(FlowNodeType.QUERY_AI,
                    Map.of("robotId", 1), "config.input.queryContent"),
            new RejectionCase(FlowNodeType.CALL_WEBHOOK,
                    Map.of("method", "POST"), "config.input.url"),
            new RejectionCase(FlowNodeType.ASYNC_TASK,
                    Map.of("dataTemplate", Map.of("k", "v")), "config.input.asyncTaskHandlerCode")
    );

    @Test
    void everyTypedTaskConfigRejectsMissingRequiredInputAtCompileTime() {
        for (RejectionCase c : CASES) {
            FlowCompileException exception = assertThrows(FlowCompileException.class,
                    () -> compiler.compile(flowWithTaskNode(c.nodeType(), c.partialInput())),
                    () -> c.nodeType() + " should fail compilation for missing " + c.expectedField());
            assertTrue(exception.getDiagnostics().stream().anyMatch(d ->
                            "MISSING_REQUIRED_INPUT".equals(d.code()) && c.expectedField().equals(d.field())),
                    () -> c.nodeType() + " expected MISSING_REQUIRED_INPUT on " + c.expectedField()
                            + " but got: " + exception.getDiagnostics());
        }
    }

    private static DesignFlowDefinition flowWithTaskNode(FlowNodeType nodeType, Map<String, Object> input) {
        return DesignFlowDefinition.builder()
                .code("typed-config-" + nodeType.getType().toLowerCase())
                .name("Typed Config " + nodeType.getType())
                .scenario(FlowScenario.PROCESS)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("task")
                                        .type(nodeType)
                                        .label("task")
                                        .config(Map.of("input", input))
                                        .build(),
                                node("end", FlowNodeType.END)
                        ))
                        .edges(List.of(
                                edge("e1", "start", "task"),
                                edge("e2", "task", "end")
                        ))
                        .build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }

    private static FlowGraphEdge edge(String id, String source, String target) {
        return FlowGraphEdge.builder().id(id).source(source).target(target).build();
    }
}
