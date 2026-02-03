package io.softa.starter.metadata.message;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.pulsar.annotation.PulsarReader;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.meta.AppStartup;
import io.softa.starter.metadata.message.dto.InnerBroadcastMessage;
import io.softa.starter.metadata.message.enums.InnerBroadcastType;

/**
 * In-app broadcast consumer, each replica reads from the latest message.
 */
@Slf4j
@Component
public class InnerBroadcastConsumer {

    @Autowired
    private AppStartup appStartup;

    /**
     * Handle inner broadcast message from Pulsar.
     * Allow missing historical messages after restart.
     * @param message Inner broadcast message
     */
    @PulsarReader(id = "inner-broadcast-reader", topics = "${mq.topics.inner-broadcast.topic}", startMessageId = "latest")
    public void onMessage(InnerBroadcastMessage message) {
        Context ctx = message.getContext();
        ContextHolder.runWith(ctx, () -> {
            if (InnerBroadcastType.RELOAD_METADATA.equals(message.getBroadcastType())) {
                appStartup.afterPropertiesSet();
            } else {
                log.warn("Unknown broadcast type: {}", message.getBroadcastType());
            }
        });
    }

}
