package io.softa.starter.flow.message;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.message.dto.FlowTimerMessage;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.nodeconfig.TimerNodeConfig;

/**
 * Publishes delayed timer wake-up messages to Pulsar.
 * <p>
 * Supports three timer strategies:
 * <ul>
 *   <li>{@code durationSeconds} — delivers the message after a fixed delay</li>
 *   <li>{@code cronExpression} — delivers at the next cron fire time</li>
 *   <li>{@code deadlineExpression} — evaluates an AviatorScript expression and delivers at that instant</li>
 * </ul>
 * <p>
 * Only activated when {@code mq.topics.flow-timer.topic} is configured.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.flow-timer.topic")
public class FlowTimerProducer {

    @Value("${mq.topics.flow-timer.topic:}")
    private String timerTopic;

    @Autowired
    private PulsarTemplate<FlowTimerMessage> pulsarTemplate;

    /**
     * Schedule a timer wake-up message based on the node config.
     *
     * @param instanceId the suspended flow instance id
     * @param nodeId     the TIMER node id
     * @param config     the timer node configuration
     * @param variables  flow variables for expression evaluation
     */
    public Instant scheduleTimer(String instanceId, String nodeId, TimerNodeConfig config,
                                 Map<String, Object> variables) {
        Assert.notBlank(timerTopic, "Flow timer topic is not configured");

        FlowTimerMessage message = new FlowTimerMessage();
        message.setInstanceId(instanceId);
        message.setNodeId(nodeId);
        Context ctx = ContextHolder.getContext();
        if (ctx != null) {
            message.setContext(ctx);
        }

        Instant deliverAt = fallbackCompute(config, variables);
        Duration rawDelay = Duration.between(Instant.now(), deliverAt);
        // Deadline already passed — deliver immediately
        long delayMillis = (rawDelay.isNegative() || rawDelay.isZero()) ? 100 : rawDelay.toMillis();

        try {
            pulsarTemplate.newMessage(message)
                    .withTopic(timerTopic)
                    .withMessageCustomizer(mb -> mb.deliverAfter(delayMillis, TimeUnit.MILLISECONDS))
                    .sendAsync()
                    .join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new FlowRuntimeException("Failed to schedule timer for instance " + instanceId
                    + " node " + nodeId + ": " + cause.getMessage(), cause);
        }
        log.debug("Scheduled timer for instance {} node {}, deliverAfter={}ms",
                instanceId, nodeId, delayMillis);
        return deliverAt;
    }

    /**
     * Compute the instant at which a TIMER node is due to fire, without publishing a message.
     * Used both by {@link #scheduleTimer} and by callers that need to persist {@code next_fire_at}
     * when the Pulsar producer is unavailable.
     */
    public static Instant fallbackCompute(TimerNodeConfig config, Map<String, Object> variables) {
        if (config.getDurationSeconds() != null) {
            return Instant.now().plusSeconds(config.getDurationSeconds());
        }

        if (config.getCronExpression() != null && !config.getCronExpression().isBlank()) {
            CronExpression cron = CronExpression.parse(config.getCronExpression());
            LocalDateTime next = cron.next(LocalDateTime.now());
            if (next == null) {
                throw new FlowRuntimeException(
                        "Cron expression '" + config.getCronExpression() + "' has no next fire time");
            }
            return next.atZone(ZoneId.systemDefault()).toInstant();
        }

        if (config.getDeadlineExpression() != null && !config.getDeadlineExpression().isBlank()) {
            Object result = ComputeUtils.execute(config.getDeadlineExpression(), variables);
            if (result instanceof Instant instant) {
                return instant;
            }
            if (result instanceof LocalDateTime ldt) {
                return ldt.atZone(ZoneId.systemDefault()).toInstant();
            }
            if (result instanceof Long epochMillis) {
                return Instant.ofEpochMilli(epochMillis);
            }
            throw new FlowRuntimeException(
                    "deadlineExpression must evaluate to Instant, LocalDateTime, or epoch millis, got: "
                            + (result == null ? "null" : result.getClass().getName()));
        }

        throw new FlowRuntimeException(
                "TimerNodeConfig must set exactly one of: durationSeconds, cronExpression, deadlineExpression");
    }
}
