package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.FlowNodeType;

/**
 * Fail-closed gate for node types whose runtime support is <strong>not yet implemented</strong>.
 * A flow must not publish a compilable node that would fail (or silently no-op) at execution
 * time, so these types are rejected here and omitted from the node palette.
 * <ul>
 *   <li>{@link FlowNodeType#FOR_EACH} — the orchestrator does not drive loop child-node
 *       iteration ({@code config.childNodeIds} is never executed). When support lands, restore
 *       the prior config-shape checks (collectionExpression / itemVariable / childNodeIds)
 *       from version control.</li>
 *   <li>{@link FlowNodeType#HUMAN_TASK} — the engine has no human-task wait: assignee
 *       resolution, a completion API, and a dedicated wait signal all do not exist. The
 *       previous implementation borrowed the approval wait and always failed at runtime
 *       (approver resolution only understands approval config). Implement it as its own
 *       wait-token type, not by reusing the approval model.</li>
 * </ul>
 */
public class UnsupportedNodeTypeValidator implements FlowValidator {

    private static final String UNSUPPORTED_NODE_TYPE = "UNSUPPORTED_NODE_TYPE";

    private static final Set<FlowNodeType> UNSUPPORTED = EnumSet.of(
            FlowNodeType.FOR_EACH,
            FlowNodeType.HUMAN_TASK
    );

    private static final Map<FlowNodeType, String> REASONS = Map.of(
            FlowNodeType.FOR_EACH,
            "the runtime does not execute loop child nodes. Remove it or model the iteration explicitly.",
            FlowNodeType.HUMAN_TASK,
            "the runtime has no human-task wait yet. Use an Approval node instead."
    );

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        for (FlowGraphNode node : context.nodeMap().values()) {
            FlowNodeType type = node.getType();
            if (type == null || !UNSUPPORTED.contains(type)) {
                continue;
            }
            d.add(CompileDiagnostic.nodeLevel(node.getId(), type.getType(), UNSUPPORTED_NODE_TYPE,
                    type.getType() + " node '" + node.getId() + "' is not supported yet: " + REASONS.get(type)));
        }
        return d;
    }
}
