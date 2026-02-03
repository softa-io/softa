package io.softa.starter.flow.node.processors;

import io.softa.starter.flow.node.NodeContext;
import io.softa.starter.flow.node.NodeProcessor;
import io.softa.starter.flow.node.params.WebHookParams;
import io.softa.starter.flow.entity.FlowNode;
import io.softa.starter.flow.enums.FlowNodeType;
import org.springframework.stereotype.Component;

/**
 * Processor for WebHook node.
 * Call the specified WebHook API.
 */
@Component
public class WebHookNode implements NodeProcessor<WebHookParams> {

    @Override
    public FlowNodeType getNodeType() {
        return FlowNodeType.WEB_HOOK;
    }

    @Override
    public Class<WebHookParams> getParamsClass() {
        return WebHookParams.class;
    }

    /**
     * Validate the parameters of the specified FlowNode under the current node processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     */
    @Override
    public void validateParams(FlowNode flowNode, WebHookParams nodeParams) {
    }

    /**
     * Execute the WebHookNode processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     * @param nodeContext The node context
     */
    @Override
    public void execute(FlowNode flowNode, WebHookParams nodeParams, NodeContext nodeContext) {
        // TODO
    }
}
