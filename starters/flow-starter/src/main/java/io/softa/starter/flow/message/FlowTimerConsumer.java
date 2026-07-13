package io.softa.starter.flow.message;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.message.dto.FlowTimerMessage;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;

/**
 * Consumes timer wake-up messages from Pulsar and resumes the suspended flow instance.
 *
 * <p>A timer scheduler (or cron-starter integration) publishes a {@link FlowTimerMessage}
 * when the configured duration, cron expression, or deadline fires. This consumer delegates
 * to {@link FlowRuntimeEngine#resumeTimer(String, String)} to advance execution past the
 * {@code TIMER} node.</p>
 *
 * <p>Only registered when {@code mq.topics.flow-timer.topic} is configured.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.flow-timer.topic")
public class FlowTimerConsumer {

    @Autowired
    private FlowRuntimeEngine runtimeEngine;

    @PulsarListener(
            topics = "${mq.topics.flow-timer.topic}",
            subscriptionName = "${mq.topics.flow-timer.sub:flow-timer-sub}",
            subscriptionType = SubscriptionType.Shared,
            deadLetterPolicy = "flowDeadLetterPolicy"
    )
    public void onMessage(FlowTimerMessage message) {
        Context ctx = message.getContext();
        Runnable task = () -> {
            runtimeEngine.resumeTimer(message.getInstanceId(), message.getNodeId());
            log.debug("Resumed flow instance {} after timer fired at node {}",
                    message.getInstanceId(), message.getNodeId());
        };

        if (ctx != null) {
            ContextHolder.runWith(ctx, task);
        } else {
            task.run();
        }
    }
}
