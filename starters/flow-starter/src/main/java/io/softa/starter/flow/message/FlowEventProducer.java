package io.softa.starter.flow.message;

import java.util.concurrent.CompletionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Assert;
import io.softa.starter.flow.message.dto.FlowEventMessage;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

/**
 * Sends flow trigger events to Pulsar for asynchronous processing.
 * <p>
 * Only activated when the topic is configured via
 * {@code mq.topics.flow-event.topic}.
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.flow-event.topic")
public class FlowEventProducer {

    @Value("${mq.topics.flow-event.topic:}")
    private String flowEventTopic;

    @Autowired
    private PulsarTemplate<FlowEventMessage> pulsarTemplate;

    /**
     * Send a flow event to MQ for asynchronous execution.
     *
     * @param message the event message
     */
    public void sendFlowEvent(FlowEventMessage message) {
        Assert.notBlank(flowEventTopic, "Flow event topic is not configured");
        try {
            pulsarTemplate.sendAsync(flowEventTopic, message).join();
            log.debug("Sent flow event to MQ: designId={}, triggerType={}",
                    message.getDesignId(), message.getTriggerType());
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new FlowRuntimeException("Failed to send flow event to MQ: " + cause.getMessage(), cause);
        }
    }
}
