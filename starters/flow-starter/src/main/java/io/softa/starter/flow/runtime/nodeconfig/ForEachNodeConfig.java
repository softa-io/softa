package io.softa.starter.flow.runtime.nodeconfig;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Config for {@code FlowNodeType.FOR_EACH} nodes.
 * <p>
 * Replaces the former {@code LoopNodeConfig} with clearer field names and an
 * explicit output-collection strategy.
 *
 * <pre>{@code
 * {
 *   "collectionExpression": "pendingOrders",
 *   "itemVariable": "order",
 *   "childNodeIds": ["validateOrder", "updateStatus"],
 *   "outputMode": "COLLECT"
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForEachNodeConfig {

    /**
     * AviatorScript expression that evaluates to a {@code List}.
     * May be a bare variable name (e.g. {@code "pendingOrders"}) or a full expression.
     */
    private String collectionExpression;

    /** Name under which the current iteration item is injected into the child context. */
    private String itemVariable;

    /** Ordered list of child node ids that form the loop body. */
    private List<String> childNodeIds;

    /**
     * Strategy for aggregating outputs across iterations.
     * <ul>
     *   <li>{@code COLLECT} — gather each iteration's step outputs into a list
     *       and write it into the parent context's steps under the ForEach node id</li>
     *   <li>{@code LAST} — only the last iteration's vars are merged back</li>
     *   <li>{@code IGNORE} — no output is propagated to the parent context</li>
     * </ul>
     */
    @Builder.Default
    private ForEachOutputMode outputMode = ForEachOutputMode.COLLECT;

    /** Maximum iterations to process; {@code null} means no limit. */
    private Integer maxIterations;

    public enum ForEachOutputMode {
        COLLECT, LAST, IGNORE
    }
}
