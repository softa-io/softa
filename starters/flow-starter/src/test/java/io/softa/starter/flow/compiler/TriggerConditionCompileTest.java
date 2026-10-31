package io.softa.starter.flow.compiler;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ChangeEvent;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.context.RecordModelFixture;

import static io.softa.starter.flow.runtime.context.RecordModelFixture.MODEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A data-change trigger's condition is checked at publish the way an edge's is — a rule against the fields of the
 * flow's model, an expression by compiling it, never both — and a field-change trigger watches fields its model has.
 */
class TriggerConditionCompileTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelManager = RecordModelFixture.mockModelManager();
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void aRuleOverTheRecordCompilesAndTravelsWithTheTrigger() {
        Filters rule = Filters.of(List.of(List.of("days", ">", 3), "AND", List.of("leaveType", "=", "Annual")));

        EntityChangeTrigger compiled = (EntityChangeTrigger) compiler.compile(change(MODEL, rule, null)).getTrigger();

        assertEquals(rule.toString(), compiled.condition().toString());
    }

    @Test
    void aTriggerKeepsOneKindOfCondition() {
        assertEquals(List.of("CONFLICTING_TRIGGER_CONDITIONS"),
                codes(change(MODEL, Filters.of(List.of("days", ">", 3)), "days > 3")));
    }

    @Test
    void aRuleIsCheckedAgainstTheFieldsOfTheFlowsModel() {
        assertTrue(message(change(MODEL, Filters.of(List.of("nights", ">", 3)), null), "INVALID_TRIGGER_CONDITION")
                .contains("does not exist"));
        assertTrue(message(change(MODEL, Filters.of(List.of("leaveType", ">", "A")), null), "INVALID_TRIGGER_CONDITION")
                .contains("has no order"));
        assertTrue(message(change("Nowhere", Filters.of(List.of("days", ">", 3)), null), "INVALID_TRIGGER_CONDITION")
                .contains("not a model"));
    }

    @Test
    void anExpressionMustCompile() {
        assertTrue(codes(change(MODEL, null, "days > 3 &&")).contains("INVALID_TRIGGER_CONDITION"));
        assertTrue(codes(change(MODEL, null, "days > 3")).isEmpty());
    }

    @Test
    void aFieldChangeTriggerWatchesFieldsItsModelHas() {
        assertTrue(message(flow(FlowScenario.COMPUTE, MODEL, new FieldChangeTrigger(List.of("days", "nights"))),
                "UNKNOWN_TRIGGER_FIELD").contains("nights"));
        assertTrue(codes(flow(FlowScenario.COMPUTE, MODEL, new FieldChangeTrigger(List.of("days")))).isEmpty());
    }

    private List<String> codes(DesignFlowDefinition definition) {
        return compiler.validate(definition).stream().map(CompileDiagnostic::code).toList();
    }

    private String message(DesignFlowDefinition definition, String code) {
        return compiler.validate(definition).stream()
                .filter(diagnostic -> code.equals(diagnostic.code()))
                .map(CompileDiagnostic::message)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " in " + compiler.validate(definition)));
    }

    private static DesignFlowDefinition change(String model, Filters rule, String expression) {
        return flow(FlowScenario.AUTOMATION, model, new EntityChangeTrigger(List.of(ChangeEvent.UPDATE), expression, rule));
    }

    private static DesignFlowDefinition flow(FlowScenario scenario, String model, TriggerSource trigger) {
        return DesignFlowDefinition.builder()
                .name("Leave watch")
                .scenario(scenario)
                .modelName(model)
                .trigger(trigger)
                .declaredOutputs(FlowScenario.COMPUTE.equals(scenario) ? List.of("days") : null)
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(FlowGraphEdge.builder().id("e1").source("start").target("end").build()))
                        .build())
                .build();
    }
}
