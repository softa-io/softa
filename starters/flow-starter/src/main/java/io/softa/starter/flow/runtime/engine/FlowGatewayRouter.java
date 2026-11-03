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
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;

/**
 * Resolves outgoing transitions for a node, including condition evaluation. Every node routes over its outgoing
 * edges, and the edge without a condition is its default — at most one per node, taken only when no condition holds:
 * <ul>
 *   <li>an ordinary node routes exclusively: it tries its conditional edges in order — by their priority, as the
 *       compiler lists them — and takes the first that holds; the conditions after it are not read. A revision
 *       compiled before priorities lists them in drawing order;</li>
 *   <li>an inclusive gateway takes every conditional edge whose condition holds, as BPMN's does — its default only
 *       when none holds;</li>
 *   <li>a parallel fork takes every edge, its conditions unread.</li>
 * </ul>
 * A node with several outgoing edges where nothing is taken fails the instance.
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

    /**
     * The first conditional edge, in order, whose condition holds — the ones after it are not decided — or, when none
     * holds, the default edge.
     */
    private List<String> resolveExclusiveEdgeNextNodeIds(CompiledFlowDefinition definition,
                                                         CompiledFlowNode node,
                                                         Predicate<CompiledFlowTransition> condition) {
        CompiledFlowTransition defaultTransition = null;
        for (String edgeId : node.getOutgoingEdgeIds()) {
            CompiledFlowTransition transition = definition.getTransitionIndex().get(edgeId);
            if (transition == null || !transition.hasCondition()) {
                continue;
            }
            if (condition.test(transition)) {
                return List.of(transition.getTarget());
            }
        }
        for (String edgeId : node.getOutgoingEdgeIds()) {
            CompiledFlowTransition transition = definition.getTransitionIndex().get(edgeId);
            if (transition == null || transition.hasCondition()) {
                continue;
            }
            if (defaultTransition != null) {
                throw new FlowRuntimeException("Node '" + node.getNodeId()
                        + "' has multiple default outgoing edges");
            }
            defaultTransition = transition;
        }
        if (defaultTransition != null) {
            return List.of(defaultTransition.getTarget());
        }
        if (node.getOutgoingEdgeIds().size() <= 1) {
            return List.of();
        }
        throw noRoute(node);
    }

    /**
     * Every conditional edge whose condition holds — each condition is decided — or, when none holds, the default
     * edge: the one without a condition.
     */
    private List<String> resolveInclusiveGatewayNextNodeIds(CompiledFlowDefinition definition,
                                                            CompiledFlowNode node,
                                                            Predicate<CompiledFlowTransition> condition) {
        List<String> matched = new ArrayList<>();
        CompiledFlowTransition defaultTransition = null;
        for (String edgeId : node.getOutgoingEdgeIds()) {
            CompiledFlowTransition transition = definition.getTransitionIndex().get(edgeId);
            if (transition == null) {
                continue;
            }
            if (!transition.hasCondition()) {
                if (defaultTransition != null) {
                    throw new FlowRuntimeException("Inclusive gateway '" + node.getNodeId()
                            + "' has multiple default outgoing edges");
                }
                defaultTransition = transition;
                continue;
            }
            if (condition.test(transition)) {
                matched.add(transition.getTarget());
            }
        }
        if (!matched.isEmpty()) {
            return matched;
        }
        if (defaultTransition != null) {
            return List.of(defaultTransition.getTarget());
        }
        throw noRoute(node);
    }

    /**
     * No route out of {@code node} fits the request, and none is the default: a gap in the flow, refused with a
     * message its reader can act on rather than failed as an unexpected error (zingkey-hcm#2382). At submission the
     * submitter is refused; after an approval the instance fails with it, and its administrators are told.
     */
    private static FlowActionValidationException noRoute(CompiledFlowNode node) {
        String step = StringUtils.hasText(node.getLabel()) ? node.getLabel() : node.getNodeId();
        return new FlowActionValidationException("No route after '" + step + "' fits this request, and the step has "
                + "no default route. An administrator needs to add one to the approval flow.");
    }
}
