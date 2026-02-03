package io.softa.starter.flow.node.processors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.ai.dto.AiUserMessage;
import io.softa.starter.ai.entity.AiMessage;
import io.softa.starter.ai.service.AiRobotService;
import io.softa.starter.flow.entity.FlowNode;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.node.NodeContext;
import io.softa.starter.flow.node.NodeProcessor;
import io.softa.starter.flow.node.params.QueryAiParams;

/**
 * Processor for QueryAi node.
 * Query AI and put the result into the node context.
 */
@Component
public class QueryAiNode implements NodeProcessor<QueryAiParams> {

    @Autowired
    private AiRobotService robotService;

    @Override
    public FlowNodeType getNodeType() {
        return FlowNodeType.QUERY_AI;
    }

    @Override
    public Class<QueryAiParams> getParamsClass() {
        return QueryAiParams.class;
    }

    /**
     * Validate the parameters of the specified FlowNode under the current node processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     */
    @Override
    public void validateParams(FlowNode flowNode, QueryAiParams nodeParams) {
        Assert.notBlank(nodeParams.getQueryContent(),
                "The query parameter for Query AI Node {0} cannot be empty.", flowNode.getName());
    }

    /**
     * Execute the QueryAiNode processor.
     * Query content supports string interpolation `#{var}`.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     * @param nodeContext The node context
     */
    @Override
    public void execute(FlowNode flowNode, QueryAiParams nodeParams, NodeContext nodeContext) {
        // Compute string interpolation
        String content = ComputeUtils.stringInterpolation(nodeParams.getQueryContent(), nodeContext.getEnv());
        AiUserMessage aiUserMessage = new AiUserMessage();
        aiUserMessage.setRobotId(nodeParams.getRobotId());
        aiUserMessage.setConversationId(nodeParams.getConversationId());
        aiUserMessage.setContent(content);
        AiMessage aiMessage = robotService.chat(aiUserMessage);
        // Put the AI reply content into the Node context
        nodeContext.put(flowNode.getId(), aiMessage.getContent());
    }
}
