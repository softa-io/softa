package io.softa.starter.flow.node.processors;

import io.softa.starter.flow.node.NodeContext;
import io.softa.starter.flow.node.NodeProcessor;
import io.softa.starter.flow.node.params.GenerateReportParams;
import io.softa.starter.flow.entity.FlowNode;
import io.softa.starter.flow.enums.FlowNodeType;
import org.springframework.stereotype.Component;

/**
 * Processor for GenerateReport node.
 * Generate the report and put the result into the node context.
 */
@Component
public class GenerateReportNode implements NodeProcessor<GenerateReportParams> {

    @Override
    public FlowNodeType getNodeType() {
        return FlowNodeType.GENERATE_REPORT;
    }

    @Override
    public Class<GenerateReportParams> getParamsClass() {
        return GenerateReportParams.class;
    }

    /**
     * Validate the parameters of the specified FlowNode under the current node processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     */
    @Override
    public void validateParams(FlowNode flowNode, GenerateReportParams nodeParams) {
    }

    /**
     * Execute the GenerateReportNode processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     * @param nodeContext The node context
     */
    @Override
    public void execute(FlowNode flowNode, GenerateReportParams nodeParams, NodeContext nodeContext) {
        // TODO
    }
}
