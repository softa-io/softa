package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

/**
 * Resolves outgoing transitions for a node, including condition evaluation.
 * Ordinary nodes use exclusive edge semantics, while inclusive gateways may
 * route to every matching edge.
 */
@Component
public class FlowGatewayRouter {

    public List<String> resolveNextNodeIds(CompiledFlowDefinition definition,
                                           CompiledFlowNode node,
                                           FlowVariableContext ctx) {
        Map<String, Object> scope = ctx.toExpressionScope();
        if (FlowNodeType.PARALLEL_FORK.equals(node.getType())) {
            return resolveAllNextNodeIds(definition, node);
        }
        if (FlowNodeType.INCLUSIVE_GATEWAY.equals(node.getType())) {
            return resolveInclusiveGatewayNextNodeIds(definition, node, scope);
        }
        return resolveExclusiveEdgeNextNodeIds(definition, node, scope);
    }

    private List<String> resolveAllNextNodeIds(CompiledFlowDefinition definition,
                                               CompiledFlowNode node) {
        List<String> nextNodeIds = new ArrayList<>();
        for (String edgeId : node.getOutgoingEdgeIds()) {
            CompiledFlowTransition transition = definition.getTransitionIndex().get(edgeId);
            if (transition != null) {
                nextNodeIds.add(transition.getTarget());
            }
        }
        return nextNodeIds;
    }

    private List<String> resolveExclusiveEdgeNextNodeIds(CompiledFlowDefinition definition,
                                                         CompiledFlowNode node,
                                                         Map<String, Object> scope) {
        List<CompiledFlowTransition> matches = new ArrayList<>();
        CompiledFlowTransition defaultTransition = null;
        for (String edgeId : node.getOutgoingEdgeIds()) {
            CompiledFlowTransition transition = definition.getTransitionIndex().get(edgeId);
            if (transition == null) {
                continue;
            }
            if (!StringUtils.hasText(transition.getConditionExpression())) {
                if (defaultTransition != null) {
                    throw new FlowRuntimeException("Node '" + node.getNodeId()
                            + "' has multiple default outgoing edges");
                }
                defaultTransition = transition;
                continue;
            }
            if (ComputeUtils.executeBoolean(transition.getConditionExpression(), new LinkedHashMap<>(scope))) {
                matches.add(transition);
            }
        }
        if (matches.size() > 1) {
            throw new FlowRuntimeException("Node '" + node.getNodeId()
                    + "' matched multiple conditional outgoing edges: "
                    + matches.stream().map(CompiledFlowTransition::getEdgeId).toList());
        }
        if (matches.size() == 1) {
            return List.of(matches.getFirst().getTarget());
        }
        if (defaultTransition != null) {
            return List.of(defaultTransition.getTarget());
        }
        if (node.getOutgoingEdgeIds().size() <= 1) {
            return List.of();
        }
        throw new FlowRuntimeException("Node '" + node.getNodeId()
                + "' did not match any outgoing edge");
    }

    private List<String> resolveInclusiveGatewayNextNodeIds(CompiledFlowDefinition definition,
                                                            CompiledFlowNode node,
                                                            Map<String, Object> scope) {
        List<String> matched = new ArrayList<>();
        for (String edgeId : node.getOutgoingEdgeIds()) {
            CompiledFlowTransition transition = definition.getTransitionIndex().get(edgeId);
            if (transition == null) {
                continue;
            }
            if (!StringUtils.hasText(transition.getConditionExpression())
                    || ComputeUtils.executeBoolean(transition.getConditionExpression(), new LinkedHashMap<>(scope))) {
                matched.add(transition.getTarget());
            }
        }
        if (matched.isEmpty()) {
            throw new FlowRuntimeException("Inclusive gateway '" + node.getNodeId()
                    + "' did not match any outgoing edge");
        }
        return matched;
    }
}
