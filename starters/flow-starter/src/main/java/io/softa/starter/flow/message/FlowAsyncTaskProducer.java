package io.softa.starter.flow.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.message.dto.FlowAsyncTaskMessage;

/**
 * Flow async task producer
 */
@Slf4j
@Component
public class FlowAsyncTaskProducer {

    @Value("${mq.topics.flow-async-task.topic}")
    private String asyncTaskTopic;

    @Autowired
    private PulsarTemplate<FlowAsyncTaskMessage> pulsarTemplate;

    /**
     * Send the async task of flow to MQ
     */
    public void sendFlowTask(FlowAsyncTaskMessage message) {
        pulsarTemplate.sendAsync(asyncTaskTopic, message).whenComplete((messageId, ex) -> {
            if (ex != null) {
                log.error("Failed to send flow async task to MQ!", ex);
            }
        });
    }
}
