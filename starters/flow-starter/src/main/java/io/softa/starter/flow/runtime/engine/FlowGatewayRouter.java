package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.domain.FilterEvaluator;
import io.softa.framework.orm.domain.FilterEvaluator.EvalContext;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.ModelManager;
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
        return resolveNextNodeIds(definition, node, transition -> holds(definition, transition, scope));
    }

    /**
     * Whether a transition's condition holds over {@code variables}: a rule condition the way a field
     * condition is judged — by the types of the record's fields — and an expression by AviatorScript.
     */
    public static boolean holds(CompiledFlowDefinition definition, CompiledFlowTransition transition,
                                Map<String, Object> variables) {
        return conditionHolds(definition.getModelName(), transition.getCondition(),
                transition.getConditionExpression(), variables);
    }

    /**
     * Whether a condition — a rule over the record of {@code modelName}, or an expression — holds over
     * {@code variables}: the rule the way a field condition is judged, by the types of the record's fields; the
     * expression by AviatorScript. An edge's and a change trigger's conditions are both decided here. No condition
     * holds.
     *
     * @throws RuntimeException when the condition cannot be evaluated — a configuration fault, not a "no"
     */
    public static boolean conditionHolds(String modelName, Filters rule, String expression, Map<String, Object> variables) {
        if (!Filters.isEmpty(rule)) {
            return FilterEvaluator.matches(rule, variables, EvalContext.of(null), field -> fieldTypeOf(modelName, field));
        }
        return !StringUtils.hasText(expression) || ComputeUtils.executeBoolean(expression, new LinkedHashMap<>(variables));
    }

    private static FieldType fieldTypeOf(String modelName, String field) {
        return modelName != null && ModelManager.existModel(modelName) && ModelManager.existField(modelName, field)
                ? ModelManager.getModelField(modelName, field).getFieldType()
                : null;
    }

    /**
     * The same routing with each edge condition decided by {@code condition}: the engine evaluates it
     * over the instance's variables, the approval path preview over what is known before submission.
     */
    public List<String> resolveNextNodeIds(CompiledFlowDefinition definition,
                                           CompiledFlowNode node,
                                           Predicate<CompiledFlowTransition> condition) {
        if (FlowNodeType.PARALLEL_FORK.equals(node.getType())) {
            return resolveAllNextNodeIds(definition, node);
        }
        if (FlowNodeType.INCLUSIVE_GATEWAY.equals(node.getType())) {
            return resolveInclusiveGatewayNextNodeIds(definition, node, condition);
        }
        return resolveExclusiveEdgeNextNodeIds(definition, node, condition);
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
                                                         Predicate<CompiledFlowTransition> condition) {
        List<CompiledFlowTransition> matches = new ArrayList<>();
        CompiledFlowTransition defaultTransition = null;
        for (String edgeId : node.getOutgoingEdgeIds()) {
            CompiledFlowTransition transition = definition.getTransitionIndex().get(edgeId);
            if (transition == null) {
                continue;
            }
            if (!transition.hasCondition()) {
                if (defaultTransition != null) {
                    throw new FlowRuntimeException("Node '" + node.getNodeId()
                            + "' has multiple default outgoing edges");
                }
                defaultTransition = transition;
                continue;
            }
            if (condition.test(transition)) {
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
                                                            Predicate<CompiledFlowTransition> condition) {
        List<String> matched = new ArrayList<>();
        for (String edgeId : node.getOutgoingEdgeIds()) {
            CompiledFlowTransition transition = definition.getTransitionIndex().get(edgeId);
            if (transition == null) {
                continue;
            }
            if (!transition.hasCondition() || condition.test(transition)) {
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
