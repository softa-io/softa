package io.softa.starter.flow.node.processors;

import io.softa.starter.flow.node.NodeContext;
import io.softa.starter.flow.node.NodeProcessor;
import io.softa.starter.flow.node.params.SendMessageParams;
import io.softa.starter.flow.entity.FlowNode;
import io.softa.starter.flow.enums.FlowNodeType;
import org.springframework.stereotype.Component;

/**
 * Processor for SendMessage node.
 * Send the message to the specified recipient.
 */
@Component
public class SendMessageNode implements NodeProcessor<SendMessageParams> {

    @Override
    public FlowNodeType getNodeType() {
        return FlowNodeType.SEND_MESSAGE;
    }

    @Override
    public Class<SendMessageParams> getParamsClass() {
        return SendMessageParams.class;
    }

    /**
     * Validate the parameters of the specified FlowNode under the current node processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     */
    @Override
    public void validateParams(FlowNode flowNode, SendMessageParams nodeParams) {
    }

    /**
     * Execute the SendMessageNode processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     * @param nodeContext The node context
     */
    @Override
    public void execute(FlowNode flowNode, SendMessageParams nodeParams, NodeContext nodeContext) {
        // TODO
    }
}
