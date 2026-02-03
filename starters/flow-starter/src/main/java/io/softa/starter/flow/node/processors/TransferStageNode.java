package io.softa.starter.flow.node.processors;

import io.softa.starter.flow.node.NodeContext;
import io.softa.starter.flow.node.NodeProcessor;
import io.softa.starter.flow.node.params.TransferStageParams;
import io.softa.starter.flow.entity.FlowNode;
import io.softa.starter.flow.enums.FlowNodeType;
import org.springframework.stereotype.Component;

/**
 * Processor for TransferStage node.
 * Transfer the stage to the specified recipient.
 */
@Component
public class TransferStageNode implements NodeProcessor<TransferStageParams> {

    @Override
    public FlowNodeType getNodeType() {
        return FlowNodeType.TRANSFER_STAGE;
    }

    @Override
    public Class<TransferStageParams> getParamsClass() {
        return TransferStageParams.class;
    }

    /**
     * Validate the parameters of the specified FlowNode under the current node processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     */
    @Override
    public void validateParams(FlowNode flowNode, TransferStageParams nodeParams) {
    }

    /**
     * Execute the TransferStageNode processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     * @param nodeContext The node context
     */
    @Override
    public void execute(FlowNode flowNode, TransferStageParams nodeParams, NodeContext nodeContext) {
        // TODO
    }
}
