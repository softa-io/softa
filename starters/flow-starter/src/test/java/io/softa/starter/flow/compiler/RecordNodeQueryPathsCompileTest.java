package io.softa.starter.flow.compiler;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.api.FlowCompileException;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.context.RecordModelFixture;

import static io.softa.starter.flow.runtime.context.RecordModelFixture.MODEL;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A record node's query is checked at publish against its model's metadata: the model must be one of the
 * application's, and what its filters, orders and fields name must be paths the query can use — so a mistyped path
 * fails the publish, where the designer shows it on the setting, rather than the run that reaches the node.
 */
class RecordNodeQueryPathsCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void aQueryOverPathsItsModelCanUsePublishes() {
        try (MockedStatic<ModelManager> ignored = RecordModelFixture.mockModelManager()) {
            assertDoesNotThrow(() -> compiler.compile(flowWith(FlowNodeType.QUERY_RECORDS, Map.of(
                    "modelName", MODEL,
                    // A read answers a field with no column of its own too.
                    "fields", List.of("days", "departmentId.costCenter", "summary"),
                    "filters", List.of(List.of("departmentId.costCenter", "=", "{{ costCenter }}"), "AND",
                            List.of("days", ">", 1)),
                    "orders", "startDate DESC"))));
        }
    }

    @Test
    void aPathTheQueryCannotUseFailsThePublishOnTheSettingThatNamesIt() {
        try (MockedStatic<ModelManager> ignored = RecordModelFixture.mockModelManager()) {
            List<CompileDiagnostic> diagnostics = rejected(flowWith(FlowNodeType.GET_RECORD, Map.of(
                    "modelName", MODEL,
                    "getDataType", "MultiRows",
                    "fields", List.of("days", "dayz"),
                    "filters", List.of(List.of("departmentId.costCentre", "=", 1), "OR", List.of("summary", "=", "x")),
                    "orders", List.of(List.of("startDate", "DESC"), List.of("attachments", "ASC")))));

            assertPathProblem(diagnostics, "config.fields", "'dayz'");
            assertPathProblem(diagnostics, "config.filters", "'departmentId.costCentre'");
            assertPathProblem(diagnostics, "config.filters", "'summary'");
            assertPathProblem(diagnostics, "config.orders", "'attachments'");
            assertEquals(4, diagnostics.stream().filter(d -> "INVALID_FIELD_PATH".equals(d.code())).count(),
                    () -> "one per path that cannot be used, got: " + diagnostics);
        }
    }

    @Test
    void theFiltersAWriteSelectsItsRowsByAreCheckedToo() {
        try (MockedStatic<ModelManager> ignored = RecordModelFixture.mockModelManager()) {
            assertPathProblem(rejected(flowWith(FlowNodeType.UPDATE_RECORD, Map.of(
                    "modelName", MODEL,
                    "filters", List.of("leaveTyp", "=", "Annual"),
                    "rowTemplate", Map.of("reason", "Moved")))), "config.filters", "'leaveTyp'");
            assertPathProblem(rejected(flowWith(FlowNodeType.DELETE_RECORD, Map.of(
                    "modelName", MODEL,
                    "filters", List.of("departmentId.parentId.nope", "=", 1)))),
                    "config.filters", "'departmentId.parentId.nope'");
        }
    }

    @Test
    void aRecordNodeOnAModelTheApplicationLacksFailsThePublish() {
        try (MockedStatic<ModelManager> ignored = RecordModelFixture.mockModelManager()) {
            List<CompileDiagnostic> created = rejected(flowWith(FlowNodeType.CREATE_RECORD, Map.of(
                    "modelName", "LeaveRequests",
                    "rowTemplate", Map.of("days", 1))));
            assertTrue(created.stream().anyMatch(d -> "UNKNOWN_MODEL".equals(d.code())
                            && "config.modelName".equals(d.field()) && d.message().contains("'LeaveRequests'")),
                    () -> "expected UNKNOWN_MODEL on config.modelName, got: " + created);

            // Its paths are not checked against a model that is not there: the model is the one problem.
            List<CompileDiagnostic> queried = rejected(flowWith(FlowNodeType.QUERY_RECORDS, Map.of(
                    "modelName", "LeaveRequests",
                    "filters", List.of("days", ">", 1))));
            assertEquals(List.of("UNKNOWN_MODEL"), queried.stream().map(CompileDiagnostic::code).toList());
        }
    }

    @Test
    void aConditionTheRunCouldNotReadFailsThePublish() {
        try (MockedStatic<ModelManager> ignored = RecordModelFixture.mockModelManager()) {
            List<CompileDiagnostic> diagnostics = rejected(flowWith(FlowNodeType.GET_RECORD, Map.of(
                    "modelName", MODEL,
                    "getDataType", "SingleRow",
                    "filters", Map.of("field", "days"))));
            assertTrue(diagnostics.stream().anyMatch(d -> "INVALID_TASK_CONFIG".equals(d.code())
                            && "config.filters".equals(d.field())),
                    () -> "expected INVALID_TASK_CONFIG on config.filters, got: " + diagnostics);
        }
    }

    private List<CompileDiagnostic> rejected(DesignFlowDefinition definition) {
        return assertThrows(FlowCompileException.class, () -> compiler.compile(definition)).getDiagnostics();
    }

    private static void assertPathProblem(List<CompileDiagnostic> diagnostics, String field, String path) {
        assertTrue(diagnostics.stream().anyMatch(d -> "INVALID_FIELD_PATH".equals(d.code())
                        && field.equals(d.field()) && d.message().contains(path)),
                () -> "expected INVALID_FIELD_PATH on " + field + " naming " + path + ", got: " + diagnostics);
    }

    /** A flow whose one task node holds {@code config} as the designer writes it: its fields at the top. */
    private static DesignFlowDefinition flowWith(FlowNodeType nodeType, Map<String, Object> config) {
        return DesignFlowDefinition.builder()
                .name("Record node " + nodeType.getType())
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                node("start", FlowNodeType.START),
                                FlowGraphNode.builder().id("task").type(nodeType).label("Records").config(config).build(),
                                node("end", FlowNodeType.END)))
                        .edges(List.of(edge("e1", "start", "task"), edge("e2", "task", "end")))
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
