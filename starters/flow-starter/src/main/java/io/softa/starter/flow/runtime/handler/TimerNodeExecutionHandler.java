package io.softa.starter.flow.runtime.handler;

import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.message.FlowTimerProducer;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.nodeconfig.TimerNodeConfig;

/**
 * Handler for {@link FlowNodeType#TIMER} nodes.
 * <p>
 * Publishes a delayed {@link io.softa.starter.flow.message.dto.FlowTimerMessage} via
 * {@link FlowTimerProducer} and signals the engine to suspend the flow instance until
 * the timer fires.
 * </p>
 * <p>
 * Timer strategies (exactly one must be set on the node config):
 * <ul>
 *   <li>{@code durationSeconds} — suspend for a fixed number of seconds</li>
 *   <li>{@code cronExpression}  — resume at the next cron-schedule firing</li>
 *   <li>{@code deadlineExpression} — resume at the evaluated deadline instant</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
public class TimerNodeExecutionHandler implements NodeExecutionHandler {

    @Autowired(required = false)
    private FlowTimerProducer timerProducer;

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return FlowNodeType.TIMER.equals(flowNodeType);
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        if (!(node.getParsedConfig() instanceof TimerNodeConfig timerConfig)) {
            throw new FlowRuntimeException("Timer node '" + node.getNodeId() + "' must configure config.timer");
        }

        Map<String, Object> scope = ctx.toExpressionScope();
        String instanceId = scope.get("_instanceId") instanceof String s ? s : null;

        Instant deliverAt;
        if (timerProducer != null) {
            deliverAt = timerProducer.scheduleTimer(instanceId, node.getNodeId(), timerConfig, scope);
            log.debug("Timer scheduled for node {} at {}", node.getNodeId(), deliverAt);
        } else {
            // No producer available — still compute deliverAt so cron-sweep can recover the instance.
            deliverAt = FlowTimerProducer.fallbackCompute(timerConfig, scope);
            log.warn("FlowTimerProducer is not available — timer node {} suspended; relying on "
                    + "flow_timer_sweep cron to resume at {}.", node.getNodeId(), deliverAt);
        }

        return new NodeOutcome.WaitTimer(deliverAt);
    }
}
