package io.softa.starter.flow.message;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.message.dto.FlowEventMessage;
import io.softa.starter.flow.service.FlowConfigService;

/**
 * Flow event consumer
 */
@Component
public class FlowEventConsumer {

    @Autowired
    private FlowConfigService flowConfigService;

    @Value("${enable.flow:true}")
    private Boolean enableFlow;

    /**
     * Flow event message consumption, persist the flow event,
     * and trigger the related flow according to the flow configuration.
     *
     * @param eventMessage Event message
     */
    @PulsarListener(topics = "${mq.topics.flow-event.topic}", subscriptionName = "${mq.topics.flow-event.sub}")
    public void onMessage(FlowEventMessage eventMessage) {
        if (!enableFlow) {
            return;
        }
        Context ctx = eventMessage.getContext();
        ContextHolder.runWith(ctx, () -> {
            if (Boolean.TRUE.equals(eventMessage.getRollbackOnFail())) {
                // Transactional Flow
                flowConfigService.executeTransactionalFlow(eventMessage);
            } else {
                flowConfigService.executeFlow(eventMessage);
            }
        });
    }

}
