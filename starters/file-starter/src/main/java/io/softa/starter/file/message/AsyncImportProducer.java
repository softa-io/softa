package io.softa.starter.file.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.core.PulsarTemplate;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Assert;
import io.softa.starter.file.dto.ImportTemplateDTO;

/**
 * AsyncImportProducer
 */
@Slf4j
@Component
public class AsyncImportProducer {

    @Value("${mq.topics.async-import.topic:}")
    private String importTopic;

    @Autowired
    private PulsarTemplate<ImportTemplateDTO> pulsarTemplate;

    /**
     * Send asynchronous import task message.
     */
    public void sendAsyncImport(ImportTemplateDTO message) {
        Assert.notBlank(importTopic, "Async import topic is not configured");
        pulsarTemplate.sendAsync(importTopic, message).whenComplete((_, ex) -> {
            if (ex == null) {
                log.debug("The asynchronous import message was successfully sent: {}", message);
            } else {
                log.error("Failed to send an asynchronous import message: {}", message, ex);
            }
        });
    }

}
