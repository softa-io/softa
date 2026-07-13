package io.softa.starter.flow.message;

import java.util.concurrent.CompletionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Assert;
import io.softa.starter.flow.message.dto.FlowAsyncTaskMessage;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

/**
 * Sends asynchronous task messages to Pulsar for deferred execution.
 * <p>
 * Only activated when the topic is configured via
 * {@code mq.topics.flow-async-task.topic}.
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.flow-async-task.topic")
public class FlowAsyncTaskProducer {

    @Value("${mq.topics.flow-async-task.topic:}")
    private String asyncTaskTopic;

    @Autowired
    private PulsarTemplate<FlowAsyncTaskMessage> pulsarTemplate;

    /**
     * Send an async task message to MQ.
     *
     * @param message the async task message
     */
    public void sendAsyncTask(FlowAsyncTaskMessage message) {
        Assert.notBlank(asyncTaskTopic, "Flow async task topic is not configured");
        try {
            pulsarTemplate.sendAsync(asyncTaskTopic, message).join();
            log.debug("Sent flow async task: handler={}", message.getAsyncTaskHandlerCode());
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new FlowRuntimeException("Failed to send flow async task to MQ: " + cause.getMessage(), cause);
        }
    }
}
