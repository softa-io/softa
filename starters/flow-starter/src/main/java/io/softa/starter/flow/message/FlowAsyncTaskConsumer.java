package io.softa.starter.flow.message;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.web.task.AsyncTaskFactory;
import io.softa.starter.flow.message.dto.FlowAsyncTaskMessage;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;

/**
 * Consumes async-task messages from Pulsar, executes them via {@link AsyncTaskFactory},
 * and resumes the suspended flow instance once the task completes.
 *
 * <p>The message carries {@code instanceId} and {@code nodeId} so the engine can
 * advance execution past the {@code ASYNC_TASK} node that originally suspended the flow.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.flow-async-task.topic")
public class FlowAsyncTaskConsumer {

    @Autowired
    private AsyncTaskFactory<?> asyncTaskFactory;

    @Autowired
    private FlowRuntimeEngine runtimeEngine;

    @PulsarListener(
            topics = "${mq.topics.flow-async-task.topic}",
            subscriptionName = "${mq.topics.flow-async-task.sub:flow-async-task-sub}",
            subscriptionType = SubscriptionType.Shared,
            deadLetterPolicy = "flowDeadLetterPolicy"
    )
    public void onMessage(FlowAsyncTaskMessage message) {
        Context ctx = message.getContext();
        ContextHolder.runWith(ctx, () -> {
            String instanceId = message.getInstanceId();
            String nodeId = message.getNodeId();

            // At-least-once dedup: Pulsar may redeliver this message (ack timeout, consumer restart,
            // nack). If the flow is no longer waiting at this async node — already resumed by an
            // earlier delivery, or the instance is terminal / gone — skip so the task body does not
            // run a second time (duplicate side effect).
            if (StringUtils.hasText(instanceId) && StringUtils.hasText(nodeId)
                    && alreadyResumed(instanceId, nodeId)) {
                log.debug("Async task redelivery for instance {} node {} already resumed; skipping",
                        instanceId, nodeId);
                return;
            }

            try {
                asyncTaskFactory.executeAsyncTask(message.getAsyncTaskHandlerCode(), message.getAsyncTaskParams());
                log.debug("Async task executed: handler={}, instanceId={}, nodeId={}",
                        message.getAsyncTaskHandlerCode(), instanceId, nodeId);
            } catch (Exception e) {
                // Task body errors are tolerated — the suspended flow must still advance.
                log.error("Async task execution failed: handler={}, instanceId={}, error={}",
                        message.getAsyncTaskHandlerCode(), instanceId, e.getMessage(), e);
            }

            // Resume the suspended flow instance regardless of task success/failure.
            // Resume failures propagate so Pulsar retries and eventually routes to DLQ.
            if (StringUtils.hasText(instanceId) && StringUtils.hasText(nodeId)) {
                runtimeEngine.resumeAsyncTask(instanceId, nodeId, Map.of());
                log.debug("Resumed flow instance {} after async task at node {}", instanceId, nodeId);
            }
        });
    }

    /** True when the instance no longer holds a live async wait for {@code nodeId} (already handled, or gone). */
    private boolean alreadyResumed(String instanceId, String nodeId) {
        return runtimeEngine.getInstance(instanceId)
                .map(state -> state.findWaitToken(nodeId) == null)
                .orElse(true);
    }
}
