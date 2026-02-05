package io.softa.starter.flow.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Assert;
import io.softa.starter.flow.message.dto.FlowEventMessage;

/**
 * Flow event producer
 */
@Slf4j
@Component
public class FlowEventProducer {

    @Value("${mq.topics.flow-event.topic:}")
    private String flowEventTopic;

    @Autowired
    private PulsarTemplate<FlowEventMessage> pulsarTemplate;

    /**
     * Send flow event to MQ.
     */
    public void sendFlowEvent(FlowEventMessage message) {
        Assert.notBlank(flowEventTopic, "Flow event topic is not configured");
        pulsarTemplate.sendAsync(flowEventTopic, message).whenComplete((_, ex) -> {
            if (ex != null) {
                log.error("Failed to send flow event to MQ!", ex);
            }
        });
    }
}
