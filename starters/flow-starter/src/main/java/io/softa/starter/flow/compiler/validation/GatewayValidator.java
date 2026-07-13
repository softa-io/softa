package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Validates routing node structure and edge-condition rules.
 */
public class GatewayValidator implements FlowValidator {

    private static final String INSUFFICIENT_GATEWAY_EDGES = "INSUFFICIENT_GATEWAY_EDGES";
    private static final String MULTIPLE_DEFAULT_EDGES     = "MULTIPLE_DEFAULT_EDGES";
    private static final String INVALID_CONDITION_EXPR     = "INVALID_CONDITION_EXPR";
    private static final String UNKNOWN_ROUTING_EDGE       = "UNKNOWN_ROUTING_EDGE";

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        for (FlowGraphNode node : context.nodeMap().values()) {
            int incoming = sizeOf(context.incomingEdgeIds().get(node.getId()));
            int outgoing = sizeOf(context.outgoingEdgeIds().get(node.getId()));

            validateOutgoingEdgeConditions(node.getId(), node.getType().getType(),
                    context.outgoingEdgeIds().get(node.getId()), context.edgeMap(), d);

            if (FlowNodeType.INCLUSIVE_GATEWAY.equals(node.getType())) {
                if (outgoing < 2) {
                    d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(),
                            INSUFFICIENT_GATEWAY_EDGES,
                            "Inclusive gateway '" + node.getId() + "' must have at least 2 outgoing edges"));
                }
            }

            if (isExclusiveEdgeNode(node) && outgoing > 1) {
                validateAtMostOneDefaultEdge(node.getId(), node.getType().getType(),
                        context.outgoingEdgeIds().get(node.getId()), context.edgeMap(), d);
            }

            if (FlowNodeType.PARALLEL_FORK.equals(node.getType()) && outgoing < 2) {
                d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(),
                        INSUFFICIENT_GATEWAY_EDGES,
                        "Parallel fork '" + node.getId() + "' must have at least 2 outgoing edges"));
            }

            if (FlowNodeType.PARALLEL_JOIN.equals(node.getType()) && incoming < 2) {
                d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(),
                        INSUFFICIENT_GATEWAY_EDGES,
                        "Parallel join '" + node.getId() + "' must have at least 2 incoming edges"));
            }
        }
        return d;
    }

    private void validateOutgoingEdgeConditions(String nodeId, String nodeType,
                                                List<String> outgoingEdgeIds,
                                                Map<String, FlowGraphEdge> edgeMap,
                                                List<CompileDiagnostic> d) {
        for (String edgeId : outgoingEdgeIds == null ? Collections.<String>emptyList() : outgoingEdgeIds) {
            FlowGraphEdge edge = edgeMap.get(edgeId);
            if (edge == null) {
                d.add(CompileDiagnostic.nodeLevel(nodeId, nodeType, UNKNOWN_ROUTING_EDGE,
                        "Node '" + nodeId + "' references unknown outgoing edge '" + edgeId + "'"));
                continue;
            }
            if (StringUtils.hasText(edge.getConditionExpression())
                    && !ComputeUtils.validateExpression(edge.getConditionExpression())) {
                d.add(CompileDiagnostic.edgeLevel(edge.getId(), nodeId, nodeType, INVALID_CONDITION_EXPR,
                        "Node '" + nodeId + "' has invalid condition expression on edge '"
                                + edge.getId() + "': " + edge.getConditionExpression()));
            }
        }
    }

    private void validateAtMostOneDefaultEdge(String nodeId, String nodeType,
                                              List<String> outgoingEdgeIds,
                                              Map<String, FlowGraphEdge> edgeMap,
                                              List<CompileDiagnostic> d) {
        int defaultEdgeCount = 0;
        for (String edgeId : outgoingEdgeIds == null ? Collections.<String>emptyList() : outgoingEdgeIds) {
            FlowGraphEdge edge = edgeMap.get(edgeId);
            if (edge != null && !StringUtils.hasText(edge.getConditionExpression())) {
                defaultEdgeCount++;
            }
        }
        if (defaultEdgeCount > 1) {
            d.add(CompileDiagnostic.nodeLevel(nodeId, nodeType, MULTIPLE_DEFAULT_EDGES,
                    "Node '" + nodeId + "' can only define one default edge"));
        }
    }

    private static boolean isExclusiveEdgeNode(FlowGraphNode node) {
        return !FlowNodeType.INCLUSIVE_GATEWAY.equals(node.getType())
                && !FlowNodeType.PARALLEL_FORK.equals(node.getType());
    }

    private static int sizeOf(List<String> values) {
        return values == null ? 0 : values.size();
    }
}
