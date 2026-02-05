package io.softa.starter.flow.message;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.pulsar.annotation.PulsarListener;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.web.task.AsyncTaskFactory;
import io.softa.starter.flow.message.dto.FlowAsyncTaskMessage;

/**
 * Flow async task consumer, also the async task executor
 */
@Component
@ConditionalOnProperty(name = "mq.topics.flow-async-task.topic")
public class FlowAsyncTaskConsumer {

    @Autowired
    private AsyncTaskFactory<?> asyncTaskFactory;

    @PulsarListener(topics = "${mq.topics.flow-async-task.topic}", subscriptionName = "${mq.topics.flow-async-task.sub}")
    public void onMessage(FlowAsyncTaskMessage flowAsyncTaskMessage) {
        Context ctx = flowAsyncTaskMessage.getContext();
        ContextHolder.runWith(ctx, () ->
                asyncTaskFactory.executeAsyncTask(flowAsyncTaskMessage.getAsyncTaskHandlerCode(), flowAsyncTaskMessage.getAsyncTaskParams())
        );
    }

}
