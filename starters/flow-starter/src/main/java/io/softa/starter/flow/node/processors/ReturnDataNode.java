package io.softa.starter.flow.node.processors;

import io.softa.framework.base.utils.Assert;
import io.softa.starter.flow.node.NodeContext;
import io.softa.starter.flow.node.NodeProcessor;
import io.softa.starter.flow.node.params.ReturnDataParams;
import io.softa.starter.flow.entity.FlowNode;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.utils.FlowUtils;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Processor for ReturnData node.
 * Return the data to the node context.
 */
@Component
public class ReturnDataNode implements NodeProcessor<ReturnDataParams> {

    @Override
    public FlowNodeType getNodeType() {
        return FlowNodeType.RETURN_DATA;
    }

    @Override
    public Class<ReturnDataParams> getParamsClass() {
        return ReturnDataParams.class;
    }

    /**
     * Validate the parameters of the specified FlowNode under the current node processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     */
    @Override
    public void validateParams(FlowNode flowNode, ReturnDataParams nodeParams) {
        Assert.notEmpty(nodeParams.getDataTemplate(),
                "The data template configuration for ReturnDataNode {0} cannot be blank.",
                flowNode.getName());
    }

    /**
     * Execute the ReturnDataNode processor.
     * The value supports constants, variables, and calculation formulas,
     * where variables are represented by `#{}` and calculation formulas are represented by `${}`.
     * Example:
     * <p>
     * {
     *     "dataTemplate":  {
     *         "parentId": "#{parentId}",
     *         "name": "#{deptName}",
     *         "ownId": "#{ownId}"
     *     }
     * }
     * </p>
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     * @param nodeContext The node context
     */
    @Override
    public void execute(FlowNode flowNode, ReturnDataParams nodeParams, NodeContext nodeContext) {
        Map<String, Object> dataMap = FlowUtils.resolveDataTemplate(nodeParams.getDataTemplate(), nodeContext);
        nodeContext.setReturnData(dataMap);
    }

}
