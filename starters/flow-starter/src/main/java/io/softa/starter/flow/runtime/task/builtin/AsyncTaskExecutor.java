package io.softa.starter.flow.runtime.task.builtin;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.message.FlowAsyncTaskProducer;
import io.softa.starter.flow.message.dto.FlowAsyncTaskMessage;
import io.softa.starter.flow.runtime.nodeconfig.AsyncTaskConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

/**
 * Built-in ServiceTask executor for dispatching an asynchronous task via MQ.
 * <p>
 * Sends a {@link FlowAsyncTaskMessage} to Pulsar; the consumer side
 * delegates to the registered {@code AsyncTaskFactory} handler.
 * </p>
 * <p>
 * Only registered when {@link FlowAsyncTaskProducer} is available
 * (i.e. when {@code mq.topics.flow-async-task.topic} is configured).
 * </p>
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "AsyncTask",
 *   "input": {
 *     "asyncTaskHandlerCode": "generateInvoice",
 *     "dataTemplate": {
 *       "orderId": "{{ orderId }}",
 *       "amount": "{{ totalAmount }}"
 *     }
 *   }
 * }
 * }</pre>
 */
@Slf4j
@Component
@ConditionalOnBean(FlowAsyncTaskProducer.class)
public class AsyncTaskExecutor extends AbstractTaskExecutor {

    @Autowired
    private FlowAsyncTaskProducer asyncTaskProducer;

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.ASYNC_TASK;
    }

    @Override
    public String getExecutor() {
        return "AsyncTask";
    }

    @Override
    public String getName() {
        return "Async Task";
    }

    @Override
    public String getDescription() {
        return "Dispatch an asynchronous task via MQ. The task is executed by the registered AsyncTaskFactory handler.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        return Map.of(
                "asyncTaskHandlerCode", Map.of("type", "string", "label", "Handler Code", "required", true),
                "dataTemplate", Map.of("type", "fieldMapping", "label", "Data Template")
        );
    }

    @Override
    public String getIcon() {
        return "zap";
    }

    @Override
    public int getSortOrder() {
        return 73;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        AsyncTaskConfig cfg = requireConfig(request, AsyncTaskConfig.class);
        // The handler no longer pre-resolves input, so interpolate the handler code here.
        String handlerCode = requireResolvedString(cfg.getAsyncTaskHandlerCode(),
                "asyncTaskHandlerCode", variables);

        // Resolve data template if provided
        Map<String, Object> asyncTaskParams = Map.of();
        Object dataTemplate = cfg.getDataTemplate();
        if (dataTemplate instanceof Map<?, ?> tpl) {
            asyncTaskParams = VariableResolver.resolveDataTemplate((Map<String, Object>) tpl, variables);
        }

        FlowAsyncTaskMessage message = new FlowAsyncTaskMessage();
        message.setInstanceId(request.getInstanceId());
        message.setNodeId(request.getNodeId());
        message.setFlowCode(variables.get("_flowCode") instanceof String fc ? fc : null);
        message.setAsyncTaskHandlerCode(handlerCode);
        message.setAsyncTaskParams(asyncTaskParams);
        message.setContext(ContextHolder.getContext());

        asyncTaskProducer.sendAsyncTask(message);
        log.debug("Dispatched async task: handler={}", handlerCode);

        return Map.of("dispatched", true, "asyncTaskHandlerCode", handlerCode);
    }
}

