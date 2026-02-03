package io.softa.starter.cron.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

import io.softa.starter.cron.message.dto.CronTaskMessage;

/**
 * Cron task producer.
 */
@Slf4j
@Component
public class CronTaskProducer {

    @Value("${mq.topics.cron-task.topic}")
    private String cronTaskTopic;

    @Autowired
    private PulsarTemplate<CronTaskMessage> pulsarTemplate;

    /**
     * Send cron task message.
     */
    public void sendCronTask(CronTaskMessage message) {
        pulsarTemplate.sendAsync(cronTaskTopic, message).whenComplete((messageId, ex) -> {
            if (ex == null) {
                log.debug("Cron scheduler successfully sends task execution MQ: {}", message);
            } else {
                log.error("Cron scheduler failed to send task execution MQ: {}", message, ex);
            }
        });
    }
}
