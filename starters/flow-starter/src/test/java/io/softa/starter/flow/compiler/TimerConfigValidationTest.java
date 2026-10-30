package io.softa.starter.flow.compiler;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.message.FlowTimerProducer;
import io.softa.starter.flow.runtime.nodeconfig.TimerNodeConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Timer waits one way — a duration, a cron schedule or a deadline — and its schedule speaks the cron scheduler's
 * dialect, the one a Cron trigger speaks: what publishes is what the timer then runs.
 */
class TimerConfigValidationTest {

    private final DefaultFlowCompiler compiler = new DefaultFlowCompiler();

    @Test
    void eachWayAloneCompiles() {
        assertEquals(List.of(), codes(Map.of("durationSeconds", 3600L)));
        assertEquals(List.of(), codes(Map.of("cronExpression", "0 0 9 ? * MON-FRI")));
        assertEquals(List.of(), codes(Map.of("deadlineExpression", "dueTime")));
    }

    @Test
    void aTimerWaitsExactlyOneWay() {
        assertEquals(List.of("INVALID_TIMER_CONFIG"), codes(Map.of()));
        assertEquals(List.of("INVALID_TIMER_CONFIG"),
                codes(Map.of("durationSeconds", 60L, "cronExpression", "0 0 9 ? * MON-FRI")));
        assertEquals(List.of("INVALID_TIMER_CONFIG"), codes(Map.of("durationSeconds", 0L)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // a five-field Unix expression, and Spring's "both day fields" form: Quartz refuses both
            "0 9 * * 1",
            "0 0 9 * * MON-FRI",
            // a schedule that has ended
            "0 0 9 1 1 ? 2020"})
    void aScheduleTheSchedulerWouldNotRunIsRefused(String cronExpression) {
        assertEquals(List.of("INVALID_TIMER_CRON"), codes(Map.of("cronExpression", cronExpression)));
    }

    @Test
    void aDeadlineMustCompile() {
        assertEquals(List.of("INVALID_TIMER_DEADLINE"), codes(Map.of("deadlineExpression", "dueTime +")));
    }

    @Test
    void theTimerResumesByTheQuartzReadingOfItsSchedule() {
        // Quartz numbers the days of the week from Sunday: 2 is Monday (Spring would read Tuesday).
        TimerNodeConfig config = TimerNodeConfig.builder().cronExpression("0 0 9 ? * 2").build();

        ZonedDateTime resumesAt = FlowTimerProducer.fallbackCompute(config, new HashMap<>()).atZone(ZoneId.systemDefault());

        assertEquals(DayOfWeek.MONDAY, resumesAt.getDayOfWeek());
        assertEquals(9, resumesAt.getHour());
    }

    private List<String> codes(Map<String, Object> timer) {
        return validate(timer).stream().map(CompileDiagnostic::code).toList();
    }

    @Test
    void theRefusalNamesTheTimerAndItsExpression() {
        String message = validate(Map.of("cronExpression", "0 9 * * 1")).getFirst().message();

        assertTrue(message.contains("Timer 'Wait'") && message.contains("0 9 * * 1"), message);
    }

    private List<CompileDiagnostic> validate(Map<String, Object> timer) {
        return compiler.validate(DesignFlowDefinition.builder()
                .name("Reminder")
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new ApiTrigger(null))
                .graph(FlowGraphDocument.builder()
                        .nodes(List.of(
                                FlowGraphNode.builder().id("start").type(FlowNodeType.START).label("start").build(),
                                FlowGraphNode.builder().id("wait").type(FlowNodeType.TIMER).label("Wait")
                                        .config(timer).build(),
                                FlowGraphNode.builder().id("end").type(FlowNodeType.END).label("end").build()))
                        .edges(List.of(
                                FlowGraphEdge.builder().id("e1").source("start").target("wait").build(),
                                FlowGraphEdge.builder().id("e2").source("wait").target("end").build()))
                        .build())
                .build());
    }
}
