package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
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
 * Validates routing node structure and edge-condition rules. A node has at most one default edge — the one without a
 * condition — an inclusive gateway included, and tries it last. The branches of an inclusive gateway either each end
 * on their own or all meet at one join, paired with the gateway ({@link BranchRegions#pairOf}): the engine counts the
 * branches the gateway does not take as arrived there. Branches that may run together meet only at a join or an end,
 * a join goes on by one edge, and a fork's branch that goes on to a join enters it by one edge on every path —
 * otherwise a step runs once per branch, or a join waits for ever and the flow finishes there.
 * {@link io.softa.starter.flow.runtime.engine.FlowExecutionOrchestrator} fails a run that does either anyway, for
 * revisions published before these checks.
 */
public class GatewayValidator implements FlowValidator {

    private static final String INSUFFICIENT_GATEWAY_EDGES = "INSUFFICIENT_GATEWAY_EDGES";
    private static final String MULTIPLE_DEFAULT_EDGES     = "MULTIPLE_DEFAULT_EDGES";
    private static final String INVALID_CONDITION_EXPR     = "INVALID_CONDITION_EXPR";
    private static final String UNKNOWN_ROUTING_EDGE       = "UNKNOWN_ROUTING_EDGE";
    private static final String CONFLICTING_EDGE_CONDITIONS = "CONFLICTING_EDGE_CONDITIONS";
    private static final String RULE_WITHOUT_RECORD        = "RULE_WITHOUT_RECORD";
    private static final String INVALID_RULE_CONDITION     = "INVALID_RULE_CONDITION";
    private static final String INCLUSIVE_JOIN_UNPAIRED    = "INCLUSIVE_JOIN_UNPAIRED";
    private static final String CONCURRENT_BRANCHES_MEET   = "CONCURRENT_BRANCHES_MEET";
    private static final String JOIN_MULTIPLE_OUTGOING     = "JOIN_MULTIPLE_OUTGOING";
    private static final String BRANCH_ENTERS_JOIN_TWICE   = "BRANCH_ENTERS_JOIN_TWICE";
    private static final String BRANCH_SKIPS_JOIN          = "BRANCH_SKIPS_JOIN";
    private static final String DEFAULT_EDGE_NOT_LAST      = "DEFAULT_EDGE_NOT_LAST";

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
                BranchRegions.Pairing pairing = BranchRegions.pairOf(node, context);
                if (pairing.problem() != null) {
                    d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(), INCLUSIVE_JOIN_UNPAIRED,
                            "Inclusive gateway '" + node.getId() + "': " + pairing.problem() + ". Its branches either "
                                    + "each end on their own, or all meet at one join: every branch, the default "
                                    + "included, goes on to it and enters it by one edge, never ending or reaching "
                                    + "another join first, sharing no node with another branch, and no edge from "
                                    + "outside comes in. Only then can the branches it does not take be counted as "
                                    + "arrived; otherwise the join waits for ever and the flow finishes there."));
                } else if (!pairing.paired()) {
                    // Its conditional edges may all hold at once; the default is taken only when none does.
                    validateConcurrentBranchesDoNotMeet(node, BranchRegions.outgoingEdges(node.getId(), context).stream()
                            .filter(FlowGraphEdge::hasCondition).toList(), context, d);
                }
            }

            if (FlowNodeType.PARALLEL_FORK.equals(node.getType()) && outgoing >= 2) {
                validateConcurrentBranchesDoNotMeet(node, BranchRegions.outgoingEdges(node.getId(), context), context, d);
                validateForkBranchesJoinOnce(node, context, d);
            }

            if (FlowNodeType.PARALLEL_JOIN.equals(node.getType()) && outgoing > 1) {
                d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(), JOIN_MULTIPLE_OUTGOING,
                        "Parallel join '" + node.getId() + "' has " + outgoing + " outgoing edges. A join waits for "
                                + "its branches and goes on once, by one edge: with more it stops waiting, and each "
                                + "branch goes on by itself. Leave it one, and branch after it."));
            }

            if (!FlowNodeType.PARALLEL_FORK.equals(node.getType()) && outgoing > 1) {
                validateAtMostOneDefaultEdge(node, context.outgoingEdgeIds().get(node.getId()), context.edgeMap(), d);
            }

            if (!FlowNodeType.PARALLEL_FORK.equals(node.getType())
                    && !FlowNodeType.INCLUSIVE_GATEWAY.equals(node.getType()) && outgoing > 1) {
                validateDefaultEdgeLast(node, context, d);
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

    /**
     * At most one edge without a condition — the default, taken only when no condition holds. An inclusive gateway's
     * too: it does not take every edge without a condition alongside the ones that hold.
     */
    private void validateAtMostOneDefaultEdge(FlowGraphNode node,
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
            d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(), MULTIPLE_DEFAULT_EDGES,
                    FlowNodeType.INCLUSIVE_GATEWAY.equals(node.getType())
                            ? "Inclusive gateway '" + node.getId() + "' can only define one default edge: the edge "
                                    + "without a condition is taken only when no condition holds, so give the others "
                                    + "a condition"
                            : "Node '" + node.getId() + "' can only define one default edge"));
        }
    }

    /**
     * The default edge — the one without a condition — is tried last, after every conditional edge: a priority that
     * puts it before one of them says something the node does not do, so it is refused rather than ignored. A default
     * without a priority is always last.
     */
    private static void validateDefaultEdgeLast(FlowGraphNode node, FlowGraphContext context, List<CompileDiagnostic> d) {
        List<FlowGraphEdge> edges = BranchRegions.outgoingEdges(node.getId(), context);
        for (FlowGraphEdge fallback : edges) {
            if (fallback.hasCondition() || fallback.getPriority() == null) {
                continue;
            }
            boolean before = edges.stream().anyMatch(edge -> edge.hasCondition()
                    && (edge.getPriority() == null || edge.getPriority() >= fallback.getPriority()));
            if (before) {
                d.add(CompileDiagnostic.edgeLevel(fallback.getId(), node.getId(), node.getType().getType(),
                        DEFAULT_EDGE_NOT_LAST, "Edge '" + fallback.getId() + "' has no condition, so it is the default "
                                + "of '" + node.getId() + "', taken only when no other edge's condition holds: it is "
                                + "always tried last. Give it no priority, or one after every conditional edge's."));
            }
        }
    }

    /**
     * Branches that may run at the same time — every edge of a parallel fork, every conditional edge of an inclusive
     * gateway — do not meet at a node other than a parallel join or an end (End, Return Value). Meeting there, the
     * node runs once for each branch that reaches it: the step after a "finance if over 1 000, travel if abroad"
     * gateway asks HR twice. A conditional edge and the default never run together, so the usual "an extra check,
     * then HR" shape — one conditional edge and the default meeting at HR — is not refused. Branches may share an
     * end; the flow finishes once nothing waits.
     */
    private static void validateConcurrentBranchesDoNotMeet(FlowGraphNode split, List<FlowGraphEdge> branches,
                                                            FlowGraphContext context, List<CompileDiagnostic> d) {
        List<Set<String>> reached = branches.stream()
                .map(branch -> BranchRegions.walk(branch.getId(), context, false).nodes())
                .toList();
        for (int i = 0; i < branches.size(); i++) {
            for (int j = i + 1; j < branches.size(); j++) {
                Set<String> met = new LinkedHashSet<>(reached.get(i));
                met.retainAll(reached.get(j));
                if (!met.isEmpty()) {
                    d.add(CompileDiagnostic.nodeLevel(split.getId(), split.getType().getType(), CONCURRENT_BRANCHES_MEET,
                            "Branches '" + branches.get(i).getId() + "' and '" + branches.get(j).getId() + "' of '"
                                    + split.getId() + "' may run at the same time, and both reach "
                                    + met.stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "))
                                    + " without a parallel join: it would run once for each. Join them with a parallel "
                                    + "join first, or let each branch end on its own."));
                    return;
                }
            }
        }
    }

    /**
     * Each branch of a parallel fork that goes on to a join arrives there once, and always: a join waits for as many
     * arrivals as it has incoming edges. A branch entering it by two alternative edges arrives by one of them only,
     * and a branch that may reach an end or another join instead may never arrive — either way the join waits for an
     * arrival that never comes, and the approval finishes there, skipping everything after it. Followed to the first
     * join on each path, past ordinary nodes and exclusive choices; a nested fork is checked as a fork of its own.
     */
    private static void validateForkBranchesJoinOnce(FlowGraphNode fork, FlowGraphContext context,
                                                     List<CompileDiagnostic> d) {
        for (FlowGraphEdge branch : BranchRegions.outgoingEdges(fork.getId(), context)) {
            BranchRegions.Branch walk = BranchRegions.walk(branch.getId(), context, true);
            Map<String, List<String>> entries = walk.joinEntries();
            boolean reachesEnd = walk.reachesEnd();
            for (Map.Entry<String, List<String>> entry : entries.entrySet()) {
                if (entry.getValue().size() > 1) {
                    d.add(CompileDiagnostic.nodeLevel(fork.getId(), fork.getType().getType(), BRANCH_ENTERS_JOIN_TWICE,
                            "Branch '" + branch.getId() + "' of '" + fork.getId() + "' enters parallel join '"
                                    + entry.getKey() + "' by " + entry.getValue().size() + " edges ("
                                    + String.join(", ", entry.getValue()) + "), of which it takes one: the join "
                                    + "would wait for the others for ever. Bring the alternatives together before "
                                    + "the join, and enter it by one edge."));
                }
            }
            if (entries.size() > 1 || (reachesEnd && !entries.isEmpty())) {
                List<String> outs = entries.keySet().stream().map(join -> "parallel join '" + join + "'")
                        .collect(Collectors.toCollection(ArrayList::new));
                if (reachesEnd) {
                    outs.add("an end");
                }
                d.add(CompileDiagnostic.nodeLevel(fork.getId(), fork.getType().getType(), BRANCH_SKIPS_JOIN,
                        "Branch '" + branch.getId() + "' of '" + fork.getId() + "' may reach "
                                + String.join(" or ", outs) + ", one instead of another: a join it does not reach "
                                + "would wait for it for ever. Let every path of the branch reach the same join, "
                                + "or none."));
            }
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

    private static int sizeOf(List<String> values) {
        return values == null ? 0 : values.size();
    }
}
