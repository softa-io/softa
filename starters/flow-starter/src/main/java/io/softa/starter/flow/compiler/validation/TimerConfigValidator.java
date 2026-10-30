package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.engine.FlowCron;
import io.softa.starter.flow.runtime.nodeconfig.TimerNodeConfig;

/**
 * A Timer waits one way: a duration of at least one second, a cron schedule — the scheduler's dialect, as a Cron
 * trigger's, and one that fires again — or a deadline expression that compiles. Without this a Timer that says
 * none, or two, publishes and fails the instance that reaches it.
 */
public class TimerConfigValidator implements FlowValidator {

    private static final String INVALID_TIMER_CONFIG = "INVALID_TIMER_CONFIG";
    private static final String INVALID_TIMER_CRON = "INVALID_TIMER_CRON";
    private static final String INVALID_TIMER_DEADLINE = "INVALID_TIMER_DEADLINE";

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        for (FlowGraphNode node : context.nodeMap().values()) {
            if (FlowNodeType.TIMER.equals(node.getType())) {
                validateTimer(node, d);
            }
        }
        return d;
    }

    private static void validateTimer(FlowGraphNode node, List<CompileDiagnostic> d) {
        TimerNodeConfig config = node.getConfig() == null ? new TimerNodeConfig()
                : BeanTool.objectToObject(node.getConfig(), TimerNodeConfig.class);
        String name = "Timer '" + Objects.requireNonNullElse(node.getLabel(), node.getId()) + "'";
        long ways = Stream.of(config.getDurationSeconds() != null, StringUtils.hasText(config.getCronExpression()),
                StringUtils.hasText(config.getDeadlineExpression())).filter(Boolean::booleanValue).count();
        if (ways != 1) {
            d.add(diagnostic(node, INVALID_TIMER_CONFIG, name + " must wait one way: a duration, a cron schedule or a"
                    + " deadline" + (ways == 0 ? "" : ", not " + ways)));
        }
        if (config.getDurationSeconds() != null && config.getDurationSeconds() < 1) {
            d.add(diagnostic(node, INVALID_TIMER_CONFIG, name + " must wait at least one second"));
        }
        if (StringUtils.hasText(config.getCronExpression())) {
            String refusal = FlowCron.refusal(config.getCronExpression());
            if (refusal != null) {
                d.add(diagnostic(node, INVALID_TIMER_CRON, name + ": the cron expression '"
                        + config.getCronExpression() + "' cannot schedule the wait: " + refusal));
            }
        }
        if (StringUtils.hasText(config.getDeadlineExpression())
                && !ComputeUtils.validateExpression(config.getDeadlineExpression())) {
            d.add(diagnostic(node, INVALID_TIMER_DEADLINE, name + ": the deadline expression does not compile: "
                    + config.getDeadlineExpression()));
        }
    }

    private static CompileDiagnostic diagnostic(FlowGraphNode node, String code, String message) {
        return CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(), code, message);
    }
}
