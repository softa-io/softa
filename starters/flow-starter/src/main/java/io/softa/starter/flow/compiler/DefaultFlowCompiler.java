package io.softa.starter.flow.compiler;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.api.FlowCompileException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.starter.flow.compiler.graph.GraphIndexBuilder;
import io.softa.starter.flow.compiler.graph.TopologicalSorter;
import io.softa.starter.flow.compiler.transform.NodeTransformer;
import io.softa.starter.flow.compiler.validation.*;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphDocument;
import io.softa.starter.flow.design.FlowGraphEdge;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;

/**
 * Default in-memory compiler for flow graph documents.
 * <p>
 * Orchestrates graph indexing, validation, topological sorting,
 * and node/transition transformation.
 */
@Service
public class DefaultFlowCompiler {

    private final List<FlowValidator> validators;

    /**
     * The built-in validators, in execution order — the single source of truth shared by the Spring
     * path (built-ins + any host-registered {@link FlowValidator} beans) and the no-arg path
     * (built-ins only). Adding a built-in here updates both paths, so prod and tests can never drift.
     */
    public static List<FlowValidator> builtinValidators() {
        return List.of(
                new GraphStructureValidator(),
                new TriggerConsistencyValidator(),
                new TaskConfigValidator(),
                new UnsupportedNodeTypeValidator(),
                new ApprovalConfigValidator(),
                new GatewayValidator()
        );
    }

    /** Test / non-Spring construction: built-in validators only. */
    public DefaultFlowCompiler() {
        this(List.of());
    }

    /** Spring construction: built-in validators plus any host-registered {@link FlowValidator} beans. */
    @Autowired
    public DefaultFlowCompiler(List<FlowValidator> extraValidators) {
        List<FlowValidator> all = new ArrayList<>(builtinValidators());
        all.addAll(extraValidators);
        this.validators = List.copyOf(all);
    }

    /**
     * Validate without compiling: returns every diagnostic the compile pipeline would
     * raise, as a normal result instead of an exception — the editor's lint surface.
     * An empty list means the definition compiles.
     */
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition) {
        return analyze(definition).diagnostics;
    }

    public CompiledFlowDefinition compile(DesignFlowDefinition definition) {
        Analysis analysis = analyze(definition);
        if (!analysis.diagnostics.isEmpty()) {
            throw new FlowCompileException(analysis.diagnostics);
        }
        FlowGraphContext context = analysis.context;
        Map<String, CompiledFlowNode> compiledNodeIndex = NodeTransformer.compileNodes(
                context.nodeMap().values(), context.incomingEdgeIds(), context.outgoingEdgeIds());
        Map<String, CompiledFlowTransition> compiledTransitionIndex =
                NodeTransformer.compileTransitions(context.edgeMap().values());
        List<String> entryNodeIds = context.entryNodeIds();
        List<String> terminalNodeIds = context.terminalNodeIds();
        List<String> topologicalOrder = analysis.topologicalOrder;

        return CompiledFlowDefinition.builder()
                .flowCode(definition.getCode())
                .flowName(definition.getName())
                .scenario(definition.getScenario())
                .compiledAt(LocalDateTime.now())
                .trigger(definition.getTrigger())
                .forms(defaultList(definition.getForms()))
                .allowInitiatorWithdraw(Boolean.TRUE.equals(definition.getAllowInitiatorWithdraw()))
                .sync(Boolean.TRUE.equals(definition.getSync()))
                .rollbackOnFail(Boolean.TRUE.equals(definition.getRollbackOnFail()))
                .approverDedup(definition.getApproverDedup())
                .declaredOutputs(defaultList(definition.getDeclaredOutputs()))
                .entryNodeIds(entryNodeIds)
                .terminalNodeIds(terminalNodeIds)
                .topologicalOrder(topologicalOrder)
                .nodeIndex(compiledNodeIndex)
                .transitionIndex(compiledTransitionIndex)
                .capabilitySummary(NodeTransformer.buildCapabilitySummary(context.nodeMap().values()))
                .build();
    }

    /** Shared validate/compile analysis: graph indexes + every diagnostic, collected not thrown. */
    private Analysis analyze(DesignFlowDefinition definition) {
        FlowGraphDocument graph = definition == null ? null : definition.getGraph();
        List<FlowGraphNode> nodes = defaultList(graph == null ? null : graph.getNodes());
        List<FlowGraphEdge> edges = defaultList(graph == null ? null : graph.getEdges());

        List<CompileDiagnostic> structural = new ArrayList<>();
        Map<String, FlowGraphNode> nodeMap = GraphIndexBuilder.indexNodes(nodes, structural);
        Map<String, FlowGraphEdge> edgeMap = GraphIndexBuilder.indexEdges(edges, structural);
        Map<String, List<String>> incomingEdgeIds = GraphIndexBuilder.buildIncomingEdgeIds(edgeMap.values());
        Map<String, List<String>> outgoingEdgeIds = GraphIndexBuilder.buildOutgoingEdgeIds(edgeMap.values());
        List<String> entryNodeIds = GraphIndexBuilder.resolveEntryNodes(nodeMap.values(), incomingEdgeIds);
        List<String> terminalNodeIds = GraphIndexBuilder.resolveTerminalNodes(nodeMap.values(), outgoingEdgeIds);

        FlowGraphContext context = new FlowGraphContext(
                nodeMap, edgeMap, incomingEdgeIds, outgoingEdgeIds, entryNodeIds, terminalNodeIds);

        // Defective entries were skipped from the indexes, so running validators on top
        // would produce misleading follow-on errors — report the structural set alone.
        if (!structural.isEmpty()) {
            return new Analysis(context, structural, List.of());
        }

        List<CompileDiagnostic> diagnostics = new ArrayList<>();
        for (FlowValidator validator : validators) {
            diagnostics.addAll(validator.validate(definition, context));
        }
        List<String> topologicalOrder =
                TopologicalSorter.topologicalSort(nodeMap.values(), edgeMap.values(), diagnostics);
        return new Analysis(context, diagnostics, topologicalOrder);
    }

    private record Analysis(FlowGraphContext context,
                            List<CompileDiagnostic> diagnostics,
                            List<String> topologicalOrder) {
    }

    private static <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
