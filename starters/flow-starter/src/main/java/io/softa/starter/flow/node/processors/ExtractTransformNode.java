package io.softa.starter.flow.node.processors;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.StringTools;
import io.softa.starter.flow.node.NodeContext;
import io.softa.starter.flow.node.NodeProcessor;
import io.softa.starter.flow.node.params.ExtractTransformParams;
import io.softa.starter.flow.entity.FlowNode;
import io.softa.starter.flow.enums.FlowNodeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.*;

/**
 * Processor for ExtractTransform node.
 * Extract and transform data from the specified collection variable.
 */
@Slf4j
@Component
public class ExtractTransformNode implements NodeProcessor<ExtractTransformParams> {

    /**
     * Get the FlowNodeType processed by the current processor.
     *
     * @return The FlowNodeType associated with the current processor.
     */
    @Override
    public FlowNodeType getNodeType() {
        return FlowNodeType.EXTRACT_TRANSFORM;
    }

    /**
     * Get the class type of the parameters required by the current processor.
     *
     * @return The Class of the encapsulated parameters.
     */
    @Override
    public Class<ExtractTransformParams> getParamsClass() {
        return ExtractTransformParams.class;
    }

    /**
     * Validate the parameters of the specified FlowNode under the current node processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     */
    @Override
    public void validateParams(FlowNode flowNode, ExtractTransformParams nodeParams) {
        Assert.notBlank(nodeParams.getCollectionVariable(),
                "The collection parameter configuration for Extract-Transform Node {0} cannot be empty!",
                flowNode.getName());
        Assert.isTrue(StringTools.isVariable(nodeParams.getCollectionVariable()),
                "The parameter {0} for Extract-Transform Node {1} must be identified with `#{}`.",
                nodeParams.getCollectionVariable(), flowNode.getName());
        Assert.notBlank(nodeParams.getItemKey(),
                "The item key configuration for Extract-Transform Node {0} cannot be empty!",
                flowNode.getName());
    }

    /**
     * Execute the ExtractTransformNode processor.
     *
     * @param flowNode The flow node
     * @param nodeParams The parameters of the flow node.
     * @param nodeContext The node context
     */
    @Override
    public void execute(FlowNode flowNode, ExtractTransformParams nodeParams, NodeContext nodeContext) {
        Object variableValue = StringTools.extractVariable(nodeParams.getCollectionVariable(), nodeContext.getEnv());
        if (variableValue == null || (variableValue instanceof Collection<?> col && CollectionUtils.isEmpty(col))) {
            nodeContext.put(flowNode.getId(), Collections.emptySet());
        } else if (variableValue instanceof Collection<?> col) {
            Set<Object> result = new HashSet<>();
            col.forEach(row -> {
                if (row instanceof Map<?, ?> rowMap) {
                    Object val = rowMap.get(nodeParams.getItemKey());
                    if (val != null) {
                        result.add(val);
                    }
                }
            });
            nodeContext.put(flowNode.getId(), result);
        } else {
            throw new IllegalArgumentException("""
                    The value of the data source variable {0} for Extract-Transform Node {1} is not a collection: {2}.
                    """, nodeParams.getCollectionVariable(), flowNode.getName(), variableValue);
        }
    }
}
