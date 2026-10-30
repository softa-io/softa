package io.softa.starter.flow.compiler;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.api.FlowCompileException;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.CronTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Cron trigger publishes only with a schedule the cron scheduler can run: its expression is parsed at compile
 * time the way the scheduler parses it, so a flow that publishes is a flow that gets scheduled.
 */
class CronTriggerValidationTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @ParameterizedTest
    @ValueSource(strings = {"0 0 9 ? * MON-FRI", "0 */5 * * * ?", "0 0 2 1 1 ? 2099"})
    void quartzExpressionCompiles(String cronExpression) {
        assertEquals(new CronTrigger(cronExpression),
                compiler.compile(cronFlow(FlowScenario.AUTOMATION, cronExpression)).getTrigger());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankExpressionIsRejected(String cronExpression) {
        assertEquals(List.of("CRON_MISSING_EXPRESSION"), codes(cronFlow(FlowScenario.AUTOMATION, cronExpression)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // a five-field Unix expression: Quartz starts with the seconds
            "0 0 * * *",
            "every day at nine",
            "0 0 25 * * ?",
            // Quartz takes a day of the month or a day of the week, not both
            "0 0 9 1 * MON"})
    void unparsableExpressionIsRejected(String cronExpression) {
        DesignFlowDefinition definition = cronFlow(FlowScenario.AUTOMATION, cronExpression);

        List<CompileDiagnostic> diagnostics = compiler.validate(definition);

        assertEquals(List.of("CRON_INVALID_EXPRESSION"), diagnostics.stream().map(CompileDiagnostic::code).toList());
        assertTrue(diagnostics.getFirst().message().contains(cronExpression), diagnostics.getFirst().message());
        FlowCompileException refused = assertThrows(FlowCompileException.class, () -> compiler.compile(definition));
        assertEquals("CRON_INVALID_EXPRESSION", refused.getDiagnostics().getFirst().code());
    }

    @Test
    void aScheduleThatHasEndedIsRejected() {
        List<CompileDiagnostic> diagnostics = compiler.validate(cronFlow(FlowScenario.AUTOMATION, "0 0 2 1 1 ? 2020"));

        assertEquals(List.of("CRON_INVALID_EXPRESSION"), diagnostics.stream().map(CompileDiagnostic::code).toList());
        assertTrue(diagnostics.getFirst().message().contains("never fires again"), diagnostics.getFirst().message());
    }

    @ParameterizedTest
    @EnumSource(value = FlowScenario.class, names = {"AUTOMATION", "VALIDATION", "COMPUTE"})
    void everyFlowButAnApprovalMayRunOnASchedule(FlowScenario scenario) {
        assertFalse(codes(cronFlow(scenario, "0 0 9 ? * MON-FRI")).contains("TRIGGER_NOT_ALLOWED"));
    }

    @Test
    void anApprovalFlowDoesNotRunOnASchedule() {
        assertTrue(codes(cronFlow(FlowScenario.APPROVAL, "0 0 9 ? * MON-FRI")).contains("TRIGGER_NOT_ALLOWED"));
    }

    private List<String> codes(DesignFlowDefinition definition) {
        return compiler.validate(definition).stream().map(CompileDiagnostic::code).toList();
    }

    private static DesignFlowDefinition cronFlow(FlowScenario scenario, String cronExpression) {
        return DesignFlowDefinition.builder()
                .name("Daily report")
                .scenario(scenario)
                .trigger(new CronTrigger(cronExpression))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(node("start", FlowNodeType.START), node("end", FlowNodeType.END)))
                        .edges(List.of(FlowGraphEdge.builder().id("e1").source("start").target("end").build()))
                        .build())
                .build();
    }

    private static FlowGraphNode node(String id, FlowNodeType type) {
        return FlowGraphNode.builder().id(id).type(type).label(id).build();
    }
}
