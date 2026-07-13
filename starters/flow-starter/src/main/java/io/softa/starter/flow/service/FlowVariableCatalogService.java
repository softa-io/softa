package io.softa.starter.flow.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.graph.GraphIndexBuilder;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.dto.FlowVariableView;

/**
 * Computes the variables visible to a node's expressions: trigger payload keys,
 * outputs declared by upstream nodes, and engine builtins. Powers the editor's
 * expression autocomplete and {@code variableRef} pickers.
 *
 * <p>Works on the design document (drafts may be broken — structural defects are
 * tolerated; unreachable information is simply absent).</p>
 */
@Service
public class FlowVariableCatalogService {

    /** Engine-injected trigger metadata (reserved '_' namespace). */
    private static final List<String> BUILTIN_VARIABLES =
            List.of("_triggerType", "_sourceModel", "_sourceRowId");

    /**
     * Variables available to {@code nodeId}'s expressions. When {@code nodeId} is null
     * (e.g. edge conditions of a not-yet-placed node), only trigger + builtin variables
     * are returned.
     */
    public List<FlowVariableView> availableVariables(DesignFlowDefinition definition, String nodeId) {
        Map<String, FlowVariableView> byName = new LinkedHashMap<>();

        collectTriggerVariables(definition, byName);
        for (String builtin : BUILTIN_VARIABLES) {
            byName.putIfAbsent(builtin, new FlowVariableView(
                    builtin, FlowVariableView.Source.BUILTIN, null, "Trigger metadata"));
        }
        if (definition != null && definition.getGraph() != null && nodeId != null) {
            collectUpstreamOutputs(definition, nodeId, byName);
        }
        return new ArrayList<>(byName.values());
    }

    private void collectTriggerVariables(DesignFlowDefinition definition,
                                         Map<String, FlowVariableView> byName) {
        if (definition == null || !(definition.getTrigger() instanceof EntityChangeTrigger trigger)) {
            return;
        }
        String modelName = trigger.modelName();
        if (!StringUtils.hasText(modelName) || !ModelManager.existModel(modelName)) {
            return;
        }
        for (String fieldName : ModelManager.getModelFieldNames(modelName)) {
            byName.putIfAbsent(fieldName, new FlowVariableView(
                    fieldName, FlowVariableView.Source.TRIGGER, null, modelName));
        }
    }

    private void collectUpstreamOutputs(DesignFlowDefinition definition, String nodeId,
                                        Map<String, FlowVariableView> byName) {
        List<CompileDiagnostic> ignored = new ArrayList<>();
        List<FlowGraphNode> nodes = definition.getGraph().getNodes() == null
                ? List.of() : definition.getGraph().getNodes();
        List<FlowGraphEdge> edges = definition.getGraph().getEdges() == null
                ? List.of() : definition.getGraph().getEdges();
        Map<String, FlowGraphNode> nodeMap = GraphIndexBuilder.indexNodes(nodes, ignored);
        Map<String, FlowGraphEdge> edgeMap = GraphIndexBuilder.indexEdges(edges, ignored);
        Map<String, List<String>> incomingEdgeIds = GraphIndexBuilder.buildIncomingEdgeIds(edgeMap.values());

        for (String upstreamId : upstreamNodeIds(nodeId, edgeMap, incomingEdgeIds)) {
            FlowGraphNode upstream = nodeMap.get(upstreamId);
            if (upstream == null || upstream.getConfig() == null) {
                continue;
            }
            for (String variable : declaredOutputs(upstream.getConfig())) {
                byName.putIfAbsent(variable, new FlowVariableView(
                        variable, FlowVariableView.Source.NODE_OUTPUT, upstreamId,
                        StringUtils.hasText(upstream.getLabel()) ? upstream.getLabel() : upstreamId));
            }
        }
    }

    /** Reverse BFS over the edges: every node from which {@code nodeId} is reachable. */
    private static Set<String> upstreamNodeIds(String nodeId,
                                               Map<String, FlowGraphEdge> edgeMap,
                                               Map<String, List<String>> incomingEdgeIds) {
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(nodeId);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            for (String edgeId : incomingEdgeIds.getOrDefault(current, List.of())) {
                String source = edgeMap.get(edgeId).getSource();
                if (source != null && visited.add(source)) {
                    queue.add(source);
                }
            }
        }
        visited.remove(nodeId);
        return visited;
    }

    /**
     * Output variable names a node's raw config declares: {@code outputVariable}
     * (Script/Subflow/Task, also nested under {@code task}) and the keys of
     * {@code outputMapping} (Subflow/Task).
     */
    private static List<String> declaredOutputs(Map<String, Object> config) {
        List<String> outputs = new ArrayList<>();
        collectOutputKeys(config, outputs);
        Object task = config.get("task");
        if (task instanceof Map<?, ?> taskMap) {
            collectOutputKeys(taskMap, outputs);
        }
        return outputs;
    }

    private static void collectOutputKeys(Map<?, ?> config, List<String> outputs) {
        if (config.get("outputVariable") instanceof String name && !name.isBlank()) {
            outputs.add(name);
        }
        if (config.get("outputMapping") instanceof Map<?, ?> mapping) {
            for (Object key : mapping.keySet()) {
                if (key instanceof String name && !name.isBlank()) {
                    outputs.add(name);
                }
            }
        }
        if (config.get("outputExpressions") instanceof Map<?, ?> expressions) {
            for (Object key : expressions.keySet()) {
                if (key instanceof String name && !name.isBlank()) {
                    outputs.add(name);
                }
            }
        }
    }

}
