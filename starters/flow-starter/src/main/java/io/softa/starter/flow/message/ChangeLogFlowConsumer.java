package io.softa.starter.flow.message;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.changelog.message.dto.ChangeLogMessage;
import io.softa.starter.flow.FlowAutomation;

/**
 * Change log consumer for Flow
 */
@Component
@ConditionalOnProperty(name = "mq.topics.change-log.topic")
public class ChangeLogFlowConsumer {

    @Autowired
    private FlowAutomation flowAutomation;

    @PulsarListener(topics = "${mq.topics.change-log.topic}", subscriptionName = "${mq.topics.change-log.flow-sub}")
    public void onMessage(ChangeLogMessage changeLogMessage) {
        Context ctx = changeLogMessage.getContext();
        ContextHolder.runWith(ctx, () -> {
            if (ctx.isTriggerFlow()) {
                flowAutomation.triggerAsyncFlowByChangeLog(changeLogMessage.getChangeLogs());
            }
        });
    }

}
