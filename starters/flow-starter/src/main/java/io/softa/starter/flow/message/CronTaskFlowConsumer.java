package io.softa.starter.flow.message;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.cron.message.dto.CronTaskMessage;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;
import io.softa.starter.flow.runtime.trigger.FlowTriggerEvent;

/**
 * Consumes Cron task messages from Pulsar and triggers matching flows.
 * <p>
 * Maps the cron event to a {@link FlowTriggerEvent} with type {@code CRON}
 * and passes cron metadata as parameters for trigger matching.
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.cron-task.topic")
public class CronTaskFlowConsumer {

    @Autowired
    private FlowAutomationService automationService;

    @PulsarListener(
            topics = "${mq.topics.cron-task.topic}",
            subscriptionName = "${mq.topics.cron-task.flow-sub:cron-task-flow-sub}",
            subscriptionType = SubscriptionType.Shared,
            deadLetterPolicy = "flowDeadLetterPolicy"
    )
    public void onMessage(CronTaskMessage cronTaskMessage) {
        Context ctx = cronTaskMessage.getContext();
        Runnable task = () -> {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("_cronId", cronTaskMessage.getCronId());
            parameters.put("_cronName", cronTaskMessage.getCronName());
            parameters.put("lastExecTime", cronTaskMessage.getLastExecTime());
            parameters.put("triggerTime", cronTaskMessage.getTriggerTime());

            String sourceModel = cronTaskMessage.getCronId() != null
                    ? "cron:" + cronTaskMessage.getCronId()
                    : null;

            FlowTriggerEvent event = FlowTriggerEvent.builder()
                    .type("Cron")
                    .sourceModel(sourceModel)
                    .parameters(parameters)
                    .build();

            automationService.fire(event);
            log.debug("Processed cron trigger event: cronId={}, cronName={}",
                    cronTaskMessage.getCronId(), cronTaskMessage.getCronName());
        };

        if (ctx != null) {
            ContextHolder.runWith(ctx, task);
        } else {
            task.run();
        }
    }
}

