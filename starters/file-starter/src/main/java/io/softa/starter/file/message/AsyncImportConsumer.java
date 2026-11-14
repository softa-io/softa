package io.softa.starter.file.message;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.file.dto.ImportTemplateDTO;

/**
 * AsyncImportConsumer
 * <p>
 * The subscription is Shared, so every replica takes imports off the topic; under the former
 * default (Exclusive) a second replica failed to boot. Each message is one independent import.
 * Switching an existing deployment from Exclusive needs one stop-then-start release: Pulsar
 * refuses a Shared consumer while an Exclusive one is still attached.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.async-import.topic")
public class AsyncImportConsumer {

    @Autowired
    private AsyncImportHandler asyncImportHandler;

    @PulsarListener(topics = "${mq.topics.async-import.topic}", subscriptionName = "${mq.topics.async-import.sub}",
            subscriptionType = SubscriptionType.Shared)
    public void onMessage(ImportTemplateDTO importTemplateDTO) {
        Context context = importTemplateDTO.getContext();
        if (context == null) {
            // A message published before the context travelled with it. Run it rather than drop
            // it: it will fail its relation lookups the old way (tenant_id = NULL matches nothing)
            // and the history row records that, which beats a message that vanishes silently.
            log.warn("Async import message carries no context; running without one. historyId={}",
                    importTemplateDTO.getHistoryId());
            asyncImportHandler.handler(importTemplateDTO);
            return;
        }
        ContextHolder.runWith(context, () -> asyncImportHandler.handler(importTemplateDTO));
    }

}
