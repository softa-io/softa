package io.softa.starter.flow.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.graph.GraphIndexBuilder;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.dto.FlowVariableView;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.context.RecordVariables;
import io.softa.starter.flow.runtime.engine.ApproverResolutionService;

/**
 * Computes the variables visible to a node's expressions: trigger payload keys,
 * outputs declared by upstream nodes, and engine builtins. Powers the editor's
 * expression autocomplete and {@code variableRef} pickers.
 *
 * <p>Works on the design document (drafts may be broken — structural defects are
 * tolerated; unreachable information is simply absent).</p>
 *
 * <p>Type enrichment: trigger fields carry the platform {@code FieldType} wire
 * value (one vocabulary with the metadata API the editor already consumes);
 * node outputs get a conservative coarse type derived from the producing node's
 * type, or {@code null} when nothing safe can be said.</p>
 */
@Service
public class FlowVariableCatalogService {

    /** Engine-injected trigger metadata (reserved '_' namespace). */
    private static final List<String> BUILTIN_VARIABLES =
            List.of("_triggerType", "_sourceModel", "_sourceRowId");

    /** Carried by a record's approval only: the employee the record is about. */
    private static final String SUBJECT_VARIABLE = ApproverResolutionService.SUBJECT_ID_KEY;

    private static final String TYPE_STRING = "String";
    private static final String TYPE_OBJECT = "Object";
    private static final String TYPE_LIST = "List";

    /**
     * Variables available to {@code nodeId}'s expressions. When {@code nodeId} is null
     * (e.g. edge conditions of a not-yet-placed node), only trigger + builtin variables
     * are returned.
     */
    public List<FlowVariableView> availableVariables(DesignFlowDefinition definition, String nodeId) {
        return availableVariables(definition, nodeId, false);
    }

    /**
     * Variables available to {@code nodeId}'s expressions, or with {@code afterNode} to the conditions of
     * the edges leaving it — which run once the node has, so its own outputs are in scope too.
     */
    public List<FlowVariableView> availableVariables(DesignFlowDefinition definition, String nodeId,
                                                     boolean afterNode) {
        Map<String, FlowVariableView> byName = new LinkedHashMap<>();

        collectTriggerVariables(definition, byName);
        for (String builtin : BUILTIN_VARIABLES) {
            byName.putIfAbsent(builtin, new FlowVariableView(
                    builtin, FlowVariableView.Source.BUILTIN, null, "Trigger metadata",
                    TYPE_STRING, null));
        }
        if (definition != null && definition.getTrigger() instanceof SubmitTrigger) {
            byName.putIfAbsent(SUBJECT_VARIABLE, new FlowVariableView(
                    SUBJECT_VARIABLE, FlowVariableView.Source.BUILTIN, null, "The employee the record is about",
                    FieldType.LONG.getType(), null));
        }
        if (definition != null && definition.getGraph() != null && nodeId != null) {
            collectUpstreamOutputs(definition, nodeId, afterNode, byName);
        }
        return new ArrayList<>(byName.values());
    }

    /**
     * The record the flow starts on: an entity change's every field, an approval's the fields its
     * approvals route on ({@link RecordVariables}) — what the instance carries, so what a rule may read.
     */
    private void collectTriggerVariables(DesignFlowDefinition definition,
                                         Map<String, FlowVariableView> byName) {
        if (definition == null) {
            return;
        }
        String modelName = definition.getModelName();
        if (definition.getTrigger() == null || !definition.getTrigger().onRecord() || !isModel(modelName)) {
            return;
        }
        List<MetaField> fields = definition.getTrigger() instanceof SubmitTrigger
                ? RecordVariables.routedFields(modelName)
                : ModelManager.getModelFields(modelName);
        for (MetaField field : fields) {
            String fieldType = field.getFieldType() == null ? null : field.getFieldType().getType();
            byName.putIfAbsent(field.getFieldName(), new FlowVariableView(
                    field.getFieldName(), FlowVariableView.Source.TRIGGER, null, modelName,
                    fieldType, relatedModelOf(field)));
        }
    }

    private static boolean isModel(String modelName) {
        return StringUtils.hasText(modelName) && ModelManager.existModel(modelName);
    }

    private static String relatedModelOf(MetaField field) {
        return StringUtils.hasText(field.getRelatedModel()) ? field.getRelatedModel() : null;
    }

    private void collectUpstreamOutputs(DesignFlowDefinition definition, String nodeId, boolean afterNode,
                                        Map<String, FlowVariableView> byName) {
        List<CompileDiagnostic> ignored = new ArrayList<>();
        List<FlowGraphNode> nodes = definition.getGraph().getNodes() == null
                ? List.of() : definition.getGraph().getNodes();
        List<FlowGraphEdge> edges = definition.getGraph().getEdges() == null
                ? List.of() : definition.getGraph().getEdges();
        Map<String, FlowGraphNode> nodeMap = GraphIndexBuilder.indexNodes(nodes, ignored);
        Map<String, FlowGraphEdge> edgeMap = GraphIndexBuilder.indexEdges(edges, ignored);
        Map<String, List<String>> incomingEdgeIds = GraphIndexBuilder.buildIncomingEdgeIds(edgeMap.values());

        Set<String> producers = new LinkedHashSet<>(upstreamNodeIds(nodeId, edgeMap, incomingEdgeIds));
        if (afterNode) {
            producers.add(nodeId);
        }
        for (String upstreamId : producers) {
            FlowGraphNode upstream = nodeMap.get(upstreamId);
            if (upstream == null || upstream.getConfig() == null) {
                continue;
            }
            String sourceLabel = StringUtils.hasText(upstream.getLabel()) ? upstream.getLabel() : upstreamId;
            String primaryOutput = primaryOutputVariable(upstream.getConfig());
            for (String variable : declaredOutputs(upstream.getConfig())) {
                boolean isPrimary = variable.equals(primaryOutput);
                byName.putIfAbsent(variable, new FlowVariableView(
                        variable, FlowVariableView.Source.NODE_OUTPUT, upstreamId, sourceLabel,
                        isPrimary ? inferNodeOutputType(upstream) : null,
                        isPrimary ? nodeTargetModel(upstream) : null));
            }
        }
    }

    /**
     * Conservative coarse type of a node's {@code outputVariable}. Only shapes
     * guaranteed by the executor contract are asserted; everything else stays null.
     */
    static String inferNodeOutputType(FlowGraphNode node) {
        FlowNodeType type = node.getType();
        if (type == null) {
            return null;
        }
        return switch (type) {
            case GET_RECORD, CREATE_RECORD, UPDATE_RECORD -> TYPE_OBJECT;
            case QUERY_RECORDS -> TYPE_LIST;
            case QUERY_AI -> TYPE_STRING;
            default -> null;
        };
    }

    /** Target model of record-shaped data-node outputs ({@code config.modelName}). */
    static String nodeTargetModel(FlowGraphNode node) {
        FlowNodeType type = node.getType();
        if (type == null || node.getConfig() == null) {
            return null;
        }
        boolean recordShaped = switch (type) {
            case GET_RECORD, CREATE_RECORD, UPDATE_RECORD, QUERY_RECORDS -> true;
            default -> false;
        };
        if (!recordShaped) {
            return null;
        }
        Object modelName = node.getConfig().get("modelName");
        return modelName instanceof String name && StringUtils.hasText(name) ? name : null;
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

    /** The node's own {@code outputVariable} (top-level or nested under {@code task}). */
    private static String primaryOutputVariable(Map<String, Object> config) {
        if (config.get("outputVariable") instanceof String name && !name.isBlank()) {
            return name;
        }
        if (config.get("task") instanceof Map<?, ?> taskMap
                && taskMap.get("outputVariable") instanceof String nested && !nested.isBlank()) {
            return nested;
        }
        return null;
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
