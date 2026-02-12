package io.softa.starter.flow.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.flow.entity.FlowNode;
import io.softa.starter.flow.node.NodeContext;

/**
 * FlowNode Model Service Interface
 */
public interface FlowNodeService extends EntityService<FlowNode, Long> {

    /**
     * Process flow node.
     * The actions in the same node are processed in the same transaction.
     *
     * @param flowNode flow node
     * @param nodeContext environment variables for executing flow actions, including row data that trigger the flow
     */
    void processFlowNode(FlowNode flowNode, NodeContext nodeContext);

}