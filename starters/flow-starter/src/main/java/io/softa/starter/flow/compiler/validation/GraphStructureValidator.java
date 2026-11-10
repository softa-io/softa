package io.softa.starter.flow.compiler.validation;

import java.util.*;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowCcRule;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubflowTrigger;
import io.softa.starter.flow.enums.ApproverSourceType;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.WithdrawPolicy;

/**
 * Validates definition-level fields, edge endpoints, the single-Start rule, that every
 * node is reachable from the Start, the node types each scenario allows and the
 * settings that belong to one scenario.
 */
public class GraphStructureValidator implements FlowValidator {

    private static final String MISSING_DEFINITION        = "MISSING_DEFINITION";
    private static final String MISSING_FLOW_NAME         = "MISSING_FLOW_NAME";
    private static final String MISSING_SCENARIO          = "MISSING_SCENARIO";
    private static final String EMPTY_GRAPH               = "EMPTY_GRAPH";
    private static final String ROLLBACK_WITHOUT_SYNC     = "ROLLBACK_WITHOUT_SYNC";
    private static final String UNKNOWN_EDGE_SOURCE       = "UNKNOWN_EDGE_SOURCE";
    private static final String UNKNOWN_EDGE_TARGET       = "UNKNOWN_EDGE_TARGET";
    private static final String NO_START_NODE             = "NO_START_NODE";
    private static final String MULTIPLE_START_NODES      = "MULTIPLE_START_NODES";
    private static final String UNREACHABLE_NODE          = "UNREACHABLE_NODE";
    private static final String MISSING_NODE_LABEL        = "MISSING_NODE_LABEL";
    private static final String INVALID_SCENARIO_NODE     = "INVALID_SCENARIO_NODE";
    private static final String SYNC_OUTSIDE_AUTOMATION   = "SYNC_OUTSIDE_AUTOMATION";
    private static final String SYNC_FLOW_WAITS           = "SYNC_FLOW_WAITS";
    private static final String SUBFLOW_WAITS             = "SUBFLOW_WAITS";
    private static final String NO_APPROVAL_STEP          = "NO_APPROVAL_STEP";
    private static final String APPROVAL_SETTING_OUTSIDE_APPROVAL = "APPROVAL_SETTING_OUTSIDE_APPROVAL";
    private static final String INVALID_CC_RULE = "INVALID_CC_RULE";
    private static final String INVALID_URGE_INTERVAL = "INVALID_URGE_INTERVAL";
    private static final String INVALID_REMIND_AFTER_HOURS = "INVALID_REMIND_AFTER_HOURS";

    /**
     * Nodes that park the instance: a sync flow runs inside the writer's transaction, and a flow with a Subflow
     * trigger inside its caller's run — neither can wait.
     */
    private static final Set<FlowNodeType> WAITING_NODE_TYPES = EnumSet.of(
            FlowNodeType.ASYNC_TASK,
            FlowNodeType.APPROVAL
    );

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> diagnostics = new ArrayList<>();
        validateDefinition(definition, diagnostics);
        validateEdgeEndpoints(context.edgeMap().values(), context.nodeMap(), diagnostics);
        validateEntryRules(context.entryNodeIds(), diagnostics);
        validateLabels(context.nodeMap().values(), diagnostics);
        validateReachability(context, diagnostics);
        validateScenarioRules(definition, context.nodeMap().values(), diagnostics);
        validateScenarioSettings(definition, context.nodeMap().values(), diagnostics);
        return diagnostics;
    }

    private void validateDefinition(DesignFlowDefinition definition, List<CompileDiagnostic> d) {
        if (definition == null) {
            d.add(CompileDiagnostic.flowLevel(MISSING_DEFINITION, "Flow definition cannot be null"));
            return;
        }
        if (!StringUtils.hasText(definition.getName())) {
            d.add(CompileDiagnostic.flowLevel(MISSING_FLOW_NAME, "Flow name is required"));
        }
        if (definition.getScenario() == null) {
            d.add(CompileDiagnostic.flowLevel(MISSING_SCENARIO, "Flow scenario is required"));
        }
        if (definition.getGraph() == null || CollectionUtils.isEmpty(definition.getGraph().getNodes())) {
            d.add(CompileDiagnostic.flowLevel(EMPTY_GRAPH, "Flow graph must contain nodes"));
        }
        if (Boolean.TRUE.equals(definition.getRollbackOnFail()) && !Boolean.TRUE.equals(definition.getSync())) {
            d.add(CompileDiagnostic.flowLevel(ROLLBACK_WITHOUT_SYNC, "rollbackOnFail can only be enabled when sync=true"));
        }
    }

    private void validateEdgeEndpoints(Collection<FlowGraphEdge> edges, Map<String, FlowGraphNode> nodeMap,
                                       List<CompileDiagnostic> d) {
        for (FlowGraphEdge edge : edges) {
            if (!nodeMap.containsKey(edge.getSource())) {
                d.add(CompileDiagnostic.edgeLevel(edge.getId(), null, null, UNKNOWN_EDGE_SOURCE,
                        "Edge '" + edge.getId() + "' references unknown source node '" + edge.getSource() + "'"));
            }
            if (!nodeMap.containsKey(edge.getTarget())) {
                d.add(CompileDiagnostic.edgeLevel(edge.getId(), null, null, UNKNOWN_EDGE_TARGET,
                        "Edge '" + edge.getId() + "' references unknown target node '" + edge.getTarget() + "'"));
            }
        }
    }

    private void validateEntryRules(List<String> entryNodeIds, List<CompileDiagnostic> d) {
        if (entryNodeIds.isEmpty()) {
            d.add(CompileDiagnostic.flowLevel(NO_START_NODE, "Flow graph must contain a Start node"));
        } else if (entryNodeIds.size() > 1) {
            d.add(CompileDiagnostic.flowLevel(MULTIPLE_START_NODES,
                    "Flow graph must contain exactly one Start node, but found: " + entryNodeIds));
        }
    }

    /**
     * Every node needs a label: people see it as the step's name — in the approval center, the audit trail and
     * failure messages — and every diagnostic names the node by it.
     */
    private void validateLabels(Collection<FlowGraphNode> nodes, List<CompileDiagnostic> d) {
        for (FlowGraphNode node : nodes) {
            if (!StringUtils.hasText(node.getLabel())) {
                d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), "label", MISSING_NODE_LABEL,
                        "This " + node.getType().getType() + " node has no label — name it: people see the label as "
                                + "the step's name"));
            }
        }
    }

    /**
     * Every node must be reachable from the Start along the edges: an unreachable node never runs, and
     * a parallel join it feeds waits for its arrival forever. Checked under a single Start only — without
     * one the entry rules already report the graph, and every node would be "unreachable".
     */
    private void validateReachability(FlowGraphContext context, List<CompileDiagnostic> d) {
        if (context.entryNodeIds().size() != 1) {
            return;
        }
        Set<String> reached = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(context.entryNodeIds());
        while (!pending.isEmpty()) {
            String nodeId = pending.pop();
            if (!reached.add(nodeId)) {
                continue;
            }
            for (String edgeId : context.outgoingEdgeIds().getOrDefault(nodeId, List.of())) {
                // An edge to an unknown node is reported as UNKNOWN_EDGE_TARGET; it leads nowhere here.
                String target = context.edgeMap().get(edgeId).getTarget();
                if (context.nodeMap().containsKey(target)) {
                    pending.push(target);
                }
            }
        }
        for (FlowGraphNode node : context.nodeMap().values()) {
            if (!reached.contains(node.getId())) {
                d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(), UNREACHABLE_NODE,
                        "Node '" + node.displayName()
                                + "' cannot be reached from the Start node — connect it or delete it"));
            }
        }
    }

    /** Each node type declares the scenarios that may use it ({@link FlowNodeType#getScenarios()}). */
    private void validateScenarioRules(DesignFlowDefinition definition, Collection<FlowGraphNode> nodes,
                                       List<CompileDiagnostic> d) {
        // A missing scenario is reported as MISSING_SCENARIO; there is nothing to check the nodes against.
        FlowScenario scenario = definition == null ? null : definition.getScenario();
        if (scenario == null) {
            return;
        }
        for (FlowGraphNode node : nodes) {
            FlowNodeType type = node.getType();
            if (type != null && !type.getScenarios().contains(scenario)) {
                d.add(CompileDiagnostic.nodeLevel(node.getId(), type.getType(), INVALID_SCENARIO_NODE,
                        "Node '" + node.displayName() + "' of type '" + type.getType() + "' is not allowed in a '"
                                + scenario.getType() + "' flow"));
            }
        }
    }

    /**
     * Each CC rule says when, and who: a recipient source with a type — specific people ({@code Users}) naming at
     * least one, or a source that finds them.
     */
    private static void validateCcRules(List<FlowCcRule> rules, List<CompileDiagnostic> d) {
        if (rules == null) {
            return;
        }
        for (int i = 0; i < rules.size(); i++) {
            FlowCcRule rule = rules.get(i);
            String which = "CC rule " + (i + 1);
            if (rule == null || rule.getTiming() == null) {
                d.add(CompileDiagnostic.flowLevel(INVALID_CC_RULE, which + " says not when: OnSubmit, OnApproved or OnRejected"));
                continue;
            }
            Map<String, Object> source = rule.getRecipientSource();
            Object type = source == null ? null : source.get("type");
            boolean names = type != null && StringUtils.hasText(type.toString())
                    && (!ApproverSourceType.USERS.getType().equals(type) || !ApproverSourceType.namedUsers(source).isEmpty());
            if (!names) {
                d.add(CompileDiagnostic.flowLevel(INVALID_CC_RULE, which + " names no recipients"));
            } else if (ApproverSourceType.lookup(type) == null) {
                // A source this version does not know finds nobody: refused, as a step's group is.
                d.add(CompileDiagnostic.flowLevel(INVALID_CC_RULE, which + " has an unknown recipient source: " + type));
            }
        }
    }

    /**
     * Settings belong to one scenario: withdrawal, urging and approver dedup to Approval flows; sync dispatch
     * (and its rollbackOnFail) to Automation flows, which then may not wait.
     */
    private void validateScenarioSettings(DesignFlowDefinition definition, Collection<FlowGraphNode> nodes,
                                          List<CompileDiagnostic> d) {
        FlowScenario scenario = definition == null ? null : definition.getScenario();
        if (scenario == null) {
            return;
        }
        if (!FlowScenario.APPROVAL.equals(scenario)
                && ((definition.getWithdrawPolicy() != null && definition.getWithdrawPolicy() != WithdrawPolicy.NEVER)
                    || definition.getReturnEnabled() != null
                    || definition.getUrgeEnabled() != null || definition.getUrgeIntervalHours() != null
                    || definition.getRemindAfterHours() != null
                    || definition.getApproverDedup() != null || definition.getSelfApproval() != null
                    || definition.getEmailNotification() != null
                    || (definition.getCc() != null && !definition.getCc().isEmpty()))) {
            d.add(CompileDiagnostic.flowLevel(APPROVAL_SETTING_OUTSIDE_APPROVAL,
                    "withdrawPolicy, returnEnabled, urgeEnabled, urgeIntervalHours, remindAfterHours, approverDedup, "
                            + "selfApproval, emailNotification and cc apply only to Approval flows"));
        }
        if (definition.getUrgeIntervalHours() != null && definition.getUrgeIntervalHours() < 1) {
            d.add(CompileDiagnostic.flowLevel(INVALID_URGE_INTERVAL,
                    "urgeIntervalHours must be at least 1, but is " + definition.getUrgeIntervalHours()));
        }
        if (definition.getRemindAfterHours() != null && definition.getRemindAfterHours() < 1) {
            d.add(CompileDiagnostic.flowLevel(INVALID_REMIND_AFTER_HOURS,
                    "remindAfterHours must be at least 1, but is " + definition.getRemindAfterHours()));
        }
        validateCcRules(definition.getCc(), d);
        // An approval flow without an approval step approves a record as it is submitted: allowed — End and every step
        // are optional — but rarely meant, so the designer says so where the flow starts.
        if (FlowScenario.APPROVAL.equals(scenario)
                && nodes.stream().noneMatch(node -> FlowNodeType.APPROVAL.equals(node.getType()))) {
            String message = "This approval flow has no approval step: a submission is approved as soon as it is made.";
            FlowGraphNode start = nodes.stream().filter(node -> FlowNodeType.START.equals(node.getType()))
                    .findFirst().orElse(null);
            d.add((start == null
                    ? CompileDiagnostic.flowLevel(NO_APPROVAL_STEP, message)
                    : CompileDiagnostic.nodeLevel(start.getId(), FlowNodeType.START.getType(), NO_APPROVAL_STEP, message))
                    .asWarning());
        }
        boolean sync = Boolean.TRUE.equals(definition.getSync());
        if (!FlowScenario.AUTOMATION.equals(scenario) && (sync || Boolean.TRUE.equals(definition.getRollbackOnFail()))) {
            d.add(CompileDiagnostic.flowLevel(SYNC_OUTSIDE_AUTOMATION,
                    "sync and rollbackOnFail apply only to Automation flows"));
        }
        // A flow a parent's Subflow node calls runs inside the parent's run, in one go: caught here, not when a parent
        // reaches it — a submission refused, or an approval failed, for a flow no submitter can put right.
        boolean subflow = definition.getTrigger() instanceof SubflowTrigger;
        if ((sync || subflow) && FlowScenario.AUTOMATION.equals(scenario)) {
            for (FlowGraphNode node : nodes) {
                if (WAITING_NODE_TYPES.contains(node.getType())) {
                    d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(),
                            sync ? SYNC_FLOW_WAITS : SUBFLOW_WAITS,
                            (sync ? "A sync flow runs inside the writer's transaction"
                                    : "A flow with a Subflow trigger runs inside the flow that calls it, in one go,")
                                    + " and cannot wait, but node '" + node.displayName() + "' of type '"
                                    + node.getType().getType() + "' waits"));
                }
            }
        }
    }
}
