package io.softa.starter.flow.runtime.task.builtin;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.ai.dto.AiUserMessage;
import io.softa.starter.ai.entity.AiMessage;
import io.softa.starter.ai.service.AiRobotService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.QueryAiConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

/**
 * Built-in ServiceTask executor for querying AI via {@link AiRobotService}.
 * <p>
 * Only registered when {@code ai-starter} is on the classpath and
 * {@link AiRobotService} is available as a bean.
 * </p>
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "QueryAi",
 *   "input": {
 *     "robotId": 1,
 *     "queryContent": "Summarize the order {{ orderId }}"
 *   },
 *   "outputVariable": "aiReply"
 * }
 * }</pre>
 */
@Component
@ConditionalOnBean(AiRobotService.class)
public class QueryAiTaskExecutor extends AbstractTaskExecutor {

    private final AiRobotService robotService;

    public QueryAiTaskExecutor(AiRobotService robotService) {
        this.robotService = robotService;
    }

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.QUERY_AI;
    }

    @Override
    public String getExecutor() {
        return "QueryAi";
    }

    @Override
    public String getName() {
        return "Query AI";
    }

    @Override
    public String getDescription() {
        return "Query AI robot with a prompt that supports {{ expr }} interpolation. Requires ai-starter.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        return Map.of(
                "queryContent", Map.of("type", "string", "label", "Query Content", "required", true),
                "robotId", Map.of("type", "number", "label", "Robot ID"),
                "conversationId", Map.of("type", "number", "label", "Conversation ID")
        );
    }

    @Override
    public String getIcon() {
        return "cpu";
    }

    @Override
    public int getSortOrder() {
        return 72;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        QueryAiConfig cfg = requireConfig(request, QueryAiConfig.class);
        String queryContent = requireText(cfg.getQueryContent(), "queryContent");

        // Support string interpolation in queryContent
        String content = ComputeUtils.stringInterpolation(queryContent, new LinkedHashMap<>(variables));

        AiUserMessage aiUserMessage = new AiUserMessage();
        aiUserMessage.setRobotId(asLong(cfg.getRobotId()));
        aiUserMessage.setConversationId(asLong(cfg.getConversationId()));
        aiUserMessage.setContent(content);

        AiMessage aiMessage = robotService.chat(aiUserMessage);
        return Map.of("reply", aiMessage.getContent());
    }

    private Long asLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(value.toString());
    }
}

