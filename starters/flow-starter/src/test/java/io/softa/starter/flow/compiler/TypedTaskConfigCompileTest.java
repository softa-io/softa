package io.softa.starter.flow.compiler;

import java.util.Arrays;
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
import io.softa.starter.flow.runtime.nodeconfig.TaskConfigTypes;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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

    /**
     * The designer writes a task node's fields where its descriptor lists them: at the top of the config, with no
     * {@code input} map. Such a node is checked as one that nests them, and its diagnostics name the field where it
     * sits — before, it published unchecked and ran with no input.
     */
    @Test
    void aDesignedTaskNodeIsCheckedWithItsFieldsWhereTheDesignerWritesThem() {
        FlowCompileException exception = assertThrows(FlowCompileException.class,
                () -> compiler.compile(flowWithTaskConfig(FlowNodeType.CALL_WEBHOOK, Map.of("method", "POST"))));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d ->
                        "MISSING_REQUIRED_INPUT".equals(d.code()) && "config.url".equals(d.field())),
                () -> "expected MISSING_REQUIRED_INPUT on config.url but got: " + exception.getDiagnostics());

        assertDoesNotThrow(() -> compiler.compile(flowWithTaskConfig(FlowNodeType.CALL_WEBHOOK,
                Map.of("url", "https://example.com/hook", "method", "POST"))));
    }

    /**
     * A SendEmail node's attachments are file ids: a link pasted there is refused at publish — it would
     * be dead by the time the mail is read. Ids and expressions publish; what an expression resolves to
     * is checked when the node runs.
     */
    @Test
    void sendEmailAttachmentsMustBeFileIds() {
        FlowCompileException exception = assertThrows(FlowCompileException.class,
                () -> compiler.compile(flowWithTaskNode(FlowNodeType.SEND_EMAIL, Map.of(
                        "to", List.of("a@example.com"),
                        "attachments", List.of("https://storage.example/report.pdf?sig=x")))));
        assertTrue(exception.getDiagnostics().stream().anyMatch(d ->
                        "INVALID_TASK_CONFIG".equals(d.code()) && "config.input.attachments".equals(d.field())),
                () -> "expected INVALID_TASK_CONFIG on config.input.attachments but got: " + exception.getDiagnostics());

        assertDoesNotThrow(() -> compiler.compile(flowWithTaskNode(FlowNodeType.SEND_EMAIL, Map.of(
                "to", List.of("a@example.com"),
                "attachments", List.of("123", 456, "{{ fileId }}")))));
    }

    /** The first required field of each typed task node, as a node with no input lacks it. */
    private static final Map<FlowNodeType, String> FIRST_REQUIRED_FIELD = Map.ofEntries(
            Map.entry(FlowNodeType.CREATE_RECORD, "config.modelName"),
            Map.entry(FlowNodeType.UPDATE_RECORD, "config.modelName"),
            Map.entry(FlowNodeType.GET_RECORD, "config.modelName"),
            Map.entry(FlowNodeType.DELETE_RECORD, "config.modelName"),
            Map.entry(FlowNodeType.QUERY_RECORDS, "config.modelName"),
            Map.entry(FlowNodeType.SEND_EMAIL, "config.to"),
            Map.entry(FlowNodeType.SEND_INBOX_NOTIFICATION, "config.recipientIds"),
            Map.entry(FlowNodeType.GENERATE_FILE, "config.templateId"),
            Map.entry(FlowNodeType.VALIDATE_DATA, "config.expression"),
            Map.entry(FlowNodeType.TRANSFORM, "config.collectionVariable"),
            Map.entry(FlowNodeType.QUERY_AI, "config.queryContent"),
            Map.entry(FlowNodeType.CALL_WEBHOOK, "config.url"),
            Map.entry(FlowNodeType.ASYNC_TASK, "config.asyncTaskHandlerCode"));

    /**
     * A task node published with no config, an empty one, or one holding only the node's own keys is checked as an
     * empty input: its required fields are reported where the designer shows them, whatever its type — before, only
     * six types were refused, and the record nodes among the rest published unchecked and failed when they ran.
     */
    @Test
    void aTaskNodeWithNoInputIsCheckedForItsRequiredFields() {
        List<FlowNodeType> typed = Arrays.stream(FlowNodeType.values())
                .filter(type -> TaskConfigTypes.forNodeType(type) != null)
                .toList();
        assertTrue(FIRST_REQUIRED_FIELD.keySet().containsAll(typed), () -> "a case for every typed task node: " + typed);

        List<Map<String, Object>> configs = Arrays.asList(null, Map.of(), Map.of("outputVariable", "result"));
        FIRST_REQUIRED_FIELD.forEach((type, field) -> {
            for (Map<String, Object> config : configs) {
                FlowCompileException exception = assertThrows(FlowCompileException.class,
                        () -> compiler.compile(flowWithTaskConfig(type, config)),
                        () -> type + " with config " + config + " should not publish");
                assertTrue(exception.getDiagnostics().stream().anyMatch(d ->
                                "MISSING_REQUIRED_INPUT".equals(d.code()) && field.equals(d.field())),
                        () -> type + " with config " + config + " expected MISSING_REQUIRED_INPUT on " + field
                                + " but got: " + exception.getDiagnostics());
            }
        });
    }

    private static DesignFlowDefinition flowWithTaskNode(FlowNodeType nodeType, Map<String, Object> input) {
        return flowWithTaskConfig(nodeType, Map.of("input", input));
    }

    private static DesignFlowDefinition flowWithTaskConfig(FlowNodeType nodeType, Map<String, Object> config) {
        return DesignFlowDefinition.builder()
                .name("Typed Config " + nodeType.getType())
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder()
                                        .id("task")
                                        .type(nodeType)
                                        .label("task")
                                        .config(config)
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
