package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.FieldConstraints;
import io.softa.framework.orm.meta.FieldConstraints.FieldRef;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.context.RecordVariables;

/**
 * Validates routing node structure and edge-condition rules.
 */
public class GatewayValidator implements FlowValidator {

    private static final String INSUFFICIENT_GATEWAY_EDGES = "INSUFFICIENT_GATEWAY_EDGES";
    private static final String MULTIPLE_DEFAULT_EDGES     = "MULTIPLE_DEFAULT_EDGES";
    private static final String INVALID_CONDITION_EXPR     = "INVALID_CONDITION_EXPR";
    private static final String UNKNOWN_ROUTING_EDGE       = "UNKNOWN_ROUTING_EDGE";
    private static final String CONFLICTING_EDGE_CONDITIONS = "CONFLICTING_EDGE_CONDITIONS";
    private static final String RULE_WITHOUT_RECORD        = "RULE_WITHOUT_RECORD";
    private static final String INVALID_RULE_CONDITION     = "INVALID_RULE_CONDITION";

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        for (FlowGraphNode node : context.nodeMap().values()) {
            int incoming = sizeOf(context.incomingEdgeIds().get(node.getId()));
            int outgoing = sizeOf(context.outgoingEdgeIds().get(node.getId()));

            validateOutgoingEdgeConditions(node.getId(), node.getType().getType(),
                    context.outgoingEdgeIds().get(node.getId()), context.edgeMap(), definition.getTrigger(),
                    recordModelOf(definition), d);

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
                                                TriggerSource trigger,
                                                String modelName,
                                                List<CompileDiagnostic> d) {
        for (String edgeId : outgoingEdgeIds == null ? Collections.<String>emptyList() : outgoingEdgeIds) {
            FlowGraphEdge edge = edgeMap.get(edgeId);
            if (edge == null) {
                d.add(CompileDiagnostic.nodeLevel(nodeId, nodeType, UNKNOWN_ROUTING_EDGE,
                        "Node '" + nodeId + "' references unknown outgoing edge '" + edgeId + "'"));
                continue;
            }
            boolean rule = !Filters.isEmpty(edge.getCondition());
            if (rule && StringUtils.hasText(edge.getConditionExpression())) {
                d.add(CompileDiagnostic.edgeLevel(edge.getId(), nodeId, nodeType, CONFLICTING_EDGE_CONDITIONS,
                        "Edge '" + edge.getId() + "' has both a rule condition and an expression; keep one"));
            } else if (rule) {
                validateRuleCondition(edge, nodeId, nodeType, trigger, modelName, d);
            } else if (StringUtils.hasText(edge.getConditionExpression())
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
            if (edge != null && !edge.hasCondition()) {
                defaultEdgeCount++;
            }
        }
        if (defaultEdgeCount > 1) {
            d.add(CompileDiagnostic.nodeLevel(nodeId, nodeType, MULTIPLE_DEFAULT_EDGES,
                    "Node '" + nodeId + "' can only define one default edge"));
        }
    }

    /**
     * A rule condition reads the fields of the record the flow starts on, judged the way a field condition
     * is: an approval's only by the fields its approvals route on, an entity change's by its model's.
     */
    private static void validateRuleCondition(FlowGraphEdge edge, String nodeId, String nodeType,
                                              TriggerSource trigger, String modelName, List<CompileDiagnostic> d) {
        if (!StringUtils.hasText(modelName) || !ModelManager.existModel(modelName)) {
            d.add(CompileDiagnostic.edgeLevel(edge.getId(), nodeId, nodeType, RULE_WITHOUT_RECORD,
                    "Edge '" + edge.getId() + "' has a rule condition, but the flow starts on no record whose"
                            + " fields a rule could read; write an expression instead"));
            return;
        }
        String prefix = "Edge '" + edge.getId() + "': ";
        try {
            if (trigger instanceof SubmitTrigger) {
                checkApprovalRule(edge.getCondition(), modelName, prefix);
            } else {
                checkRule(edge.getCondition(), modelName, prefix);
            }
        } catch (IllegalStateException e) {
            d.add(CompileDiagnostic.edgeLevel(edge.getId(), nodeId, nodeType, INVALID_RULE_CONDITION,
                    e.getMessage()));
        }
    }

    /**
     * Checks a rule of an Approval flow — an edge's, or its submit condition — over the fields of the document
     * {@code modelName}: only the fields its approvals route on, then the way {@link #checkRule} does.
     *
     * @throws IllegalStateException naming what is wrong, after {@code prefix}
     */
    static void checkApprovalRule(Filters rule, String modelName, String prefix) {
        Set<String> routed = RecordVariables.routedFields(modelName).stream()
                .map(MetaField::getFieldName)
                .collect(Collectors.toSet());
        for (String field : rule.extractFields()) {
            if (!routed.contains(field) && ModelManager.existField(modelName, field)) {
                throw new IllegalStateException(prefix + "`" + field + "` is not one of the fields "
                        + modelName + "'s approvals route on; its identity, bookkeeping, approval"
                        + " state and fields declared sensitive are left out.");
            }
        }
        checkRule(rule, modelName, prefix);
    }

    /**
     * Checks a rule over the fields of {@code modelName} the way the annotation scan checks a field condition.
     *
     * @throws IllegalStateException naming what is wrong, after {@code prefix}
     */
    static void checkRule(Filters rule, String modelName, String prefix) {
        FieldConstraints.checkCondition(rule, prefix,
                field -> ModelManager.existField(modelName, field)
                        ? FieldRef.of(ModelManager.getModelField(modelName, field))
                        : null,
                false);
    }

    /** The model of the record the flow starts on, as its trigger names it; none when it starts on no record. */
    private static String recordModelOf(DesignFlowDefinition definition) {
        return definition.getTrigger() == null ? null : definition.getTrigger().modelName();
    }

    private static boolean isExclusiveEdgeNode(FlowGraphNode node) {
        return !FlowNodeType.INCLUSIVE_GATEWAY.equals(node.getType())
                && !FlowNodeType.PARALLEL_FORK.equals(node.getType());
    }

    private static int sizeOf(List<String> values) {
        return values == null ? 0 : values.size();
    }
}
