package io.softa.starter.flow.message;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.changelog.message.dto.ChangeLogMessage;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;
import io.softa.starter.flow.runtime.trigger.FlowTriggerEvent;

/**
 * Consumes ChangeLog messages from Pulsar and runs the async flows ({@code sync=false}) the changes start, in this
 * listener's own thread. Its subscription ({@code flow-sub}) is apart from the one that persists the change logs, so
 * a slow flow does not hold that up. The sync flows matching the same change already ran in the writer's
 * transaction, before it committed.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.change-log.topic")
public class ChangeLogFlowConsumer {

    @Autowired
    private FlowAutomationService automationService;

    @PulsarListener(
            topics = "${mq.topics.change-log.topic}",
            subscriptionName = "${mq.topics.change-log.flow-sub:change-log-flow-sub}",
            subscriptionType = SubscriptionType.Shared,
            deadLetterPolicy = "flowDeadLetterPolicy"
    )
    public void onMessage(ChangeLogMessage changeLogMessage) {
        Context ctx = changeLogMessage.getContext();
        if (ctx == null || !ctx.isTriggerFlow()) {
            return;
        }
        ContextHolder.runWith(ctx, () -> {
            String actorId = ctx.getUserId() != null ? ctx.getUserId().toString() : null;
            List<FlowTriggerEvent> events = ChangeLogTriggerMapper.mapChangeLogs(
                    changeLogMessage.getChangeLogs(), actorId, automationService::hasEntityChangeTrigger);

            for (FlowTriggerEvent event : events) {
                try {
                    automationService.fireAsyncOnly(event);
                } catch (Exception e) {
                    log.error("Failed to fire ChangeLog trigger event {}: {}", event, e.getMessage(), e);
                    throw (RuntimeException) e;
                }
            }
            if (!events.isEmpty()) {
                log.debug("Processed {} async ChangeLog trigger events from MQ", events.size());
            }
        });
    }
}
