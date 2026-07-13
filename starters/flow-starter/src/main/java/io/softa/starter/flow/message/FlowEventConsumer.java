package io.softa.starter.flow.message;

import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.SubscriptionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.message.dto.FlowEventMessage;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.trigger.FlowAutomationService;
import io.softa.starter.flow.runtime.trigger.FlowTriggerEvent;

/**
 * Consumes flow trigger events from Pulsar and executes them.
 * <p>
 * If the message specifies a {@code flowCode}, the flow is started directly.
 * Otherwise, the message is mapped to a {@link FlowTriggerEvent} and fired
 * through the trigger registry to find matching flows.
 * </p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "mq.topics.flow-event.topic")
public class FlowEventConsumer {

    @Autowired
    private FlowAutomationService automationService;

    @Autowired
    private FlowRuntimeEngine runtimeEngine;

    @PulsarListener(
            topics = "${mq.topics.flow-event.topic}",
            subscriptionName = "${mq.topics.flow-event.sub:flow-event-sub}",
            subscriptionType = SubscriptionType.Shared,
            deadLetterPolicy = "flowDeadLetterPolicy"
    )
    public void onMessage(FlowEventMessage message) {
        Context ctx = message.getContext();
        Runnable task = () -> {
            if (message.getBundleId() != null || message.getDesignId() != null) {
                startDirect(message);
            } else {
                fireTrigger(message);
            }
        };

        if (ctx != null) {
            ContextHolder.runWith(ctx, task);
        } else {
            task.run();
        }
    }

    private void startDirect(FlowEventMessage message) {
        FlowStartRequest request = new FlowStartRequest();
        request.setDesignId(message.getDesignId());
        request.setBundleId(message.getBundleId());
        request.setInitiatorId(message.getActorId());
        request.setVariables(message.getParameters());
        runtimeEngine.start(request);
        log.info("Async flow started: designId={}, bundleId={}",
                message.getDesignId(), message.getBundleId());
    }

    private void fireTrigger(FlowEventMessage message) {
        FlowTriggerEvent event = FlowTriggerEvent.builder()
                .type(message.getTriggerType())
                .sourceModel(message.getSourceModel())
                .sourceRowId(message.getSourceRowId())
                .parameters(message.getParameters())
                .actorId(message.getActorId())
                .build();
        automationService.fire(event);
        log.info("Async trigger fired: type={}, sourceModel={}", message.getTriggerType(), message.getSourceModel());
    }
}

