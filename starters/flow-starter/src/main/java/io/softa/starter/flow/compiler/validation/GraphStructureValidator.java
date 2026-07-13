package io.softa.starter.flow.compiler.validation;

import java.util.*;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Validates definition-level fields, edge endpoints, entry node rules,
 * and scenario-specific node type constraints.
 */
public class GraphStructureValidator implements FlowValidator {

    private static final String MISSING_FLOW_CODE         = "MISSING_FLOW_CODE";
    private static final String MISSING_FLOW_NAME         = "MISSING_FLOW_NAME";
    private static final String MISSING_SCENARIO          = "MISSING_SCENARIO";
    private static final String EMPTY_GRAPH               = "EMPTY_GRAPH";
    private static final String ROLLBACK_WITHOUT_SYNC     = "ROLLBACK_WITHOUT_SYNC";
    private static final String UNKNOWN_EDGE_SOURCE       = "UNKNOWN_EDGE_SOURCE";
    private static final String UNKNOWN_EDGE_TARGET       = "UNKNOWN_EDGE_TARGET";
    private static final String NO_ENTRY_NODE             = "NO_ENTRY_NODE";
    private static final String MULTIPLE_ENTRY_NODES      = "MULTIPLE_ENTRY_NODES";
    private static final String INVALID_SCENARIO_NODE     = "INVALID_SCENARIO_NODE";

    private static final Set<FlowNodeType> LIGHTWEIGHT_SCENARIO_NODE_TYPES = EnumSet.of(
            FlowNodeType.START,
            FlowNodeType.END,
            FlowNodeType.SCRIPT,
            FlowNodeType.CREATE_RECORD,
            FlowNodeType.GET_RECORD,
            FlowNodeType.UPDATE_RECORD,
            FlowNodeType.DELETE_RECORD,
            FlowNodeType.QUERY_RECORDS,
            FlowNodeType.CALL_SERVICE,
            FlowNodeType.CALL_WEBHOOK,
            FlowNodeType.RETURN_VALUE
    );

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> diagnostics = new ArrayList<>();
        validateDefinition(definition, diagnostics);
        validateEdgeEndpoints(context.edgeMap().values(), context.nodeMap(), diagnostics);
        validateEntryRules(context.entryNodeIds(), diagnostics);
        validateScenarioRules(definition, context.nodeMap().values(), diagnostics);
        return diagnostics;
    }

    private void validateDefinition(DesignFlowDefinition definition, List<CompileDiagnostic> d) {
        if (definition == null) {
            d.add(CompileDiagnostic.flowLevel(MISSING_FLOW_CODE, "Flow definition cannot be null"));
            return;
        }
        if (!StringUtils.hasText(definition.getCode())) {
            d.add(CompileDiagnostic.flowLevel(MISSING_FLOW_CODE, "Flow code is required"));
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
            d.add(CompileDiagnostic.flowLevel(NO_ENTRY_NODE, "Flow graph must contain at least one entry node"));
        } else if (entryNodeIds.size() > 1) {
            d.add(CompileDiagnostic.flowLevel(MULTIPLE_ENTRY_NODES,
                    "Flow graph must resolve to exactly one entry node, but found: " + entryNodeIds));
        }
    }

    private void validateScenarioRules(DesignFlowDefinition definition, Collection<FlowGraphNode> nodes,
                                       List<CompileDiagnostic> d) {
        if (definition == null || FlowScenario.PROCESS.equals(definition.getScenario())) {
            return;
        }
        for (FlowGraphNode node : nodes) {
            if (!LIGHTWEIGHT_SCENARIO_NODE_TYPES.contains(node.getType())) {
                d.add(CompileDiagnostic.nodeLevel(node.getId(),
                        node.getType() != null ? node.getType().getType() : null,
                        INVALID_SCENARIO_NODE,
                        "Scenario '" + definition.getScenario().getType()
                                + "' only supports lightweight nodes; node '" + node.getId() + "' of type '"
                                + (node.getType() != null ? node.getType().getType() : "null") + "' is not allowed"));
            }
        }
    }
}
