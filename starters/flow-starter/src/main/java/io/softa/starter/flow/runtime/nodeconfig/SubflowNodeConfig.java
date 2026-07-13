package io.softa.starter.flow.runtime.nodeconfig;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Config for {@code FlowNodeType.SUBFLOW} nodes.
 * <p>
 * Supports explicit input/output variable mapping so that parent and child flows
 * maintain clear variable-scope boundaries — the child does not automatically
 * inherit the parent's entire variable context.
 *
 * <pre>{@code
 * {
 *   "subflowDesignId": 1234567890,
 *   "inputMapping": {
 *     "reviewerId": "{{ currentUser }}",
 *     "docId": "{{ documentId }}"
 *   },
 *   "outputVariable": "reviewResult",
 *   "outputMapping": {
 *     "finalDecision": "{{ steps.approvalNode.decision }}"
 *   }
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubflowNodeConfig {

    /** Design id of the subflow to invoke (globally unique, tenant-safe). */
    private Long subflowDesignId;

    /**
     * Maps child-flow {@code input} keys to AviatorScript expressions evaluated
     * in the parent context.
     * <pre>{ "childKey": "{{ parentExpression }}" }</pre>
     */
    private Map<String, String> inputMapping;

    /**
     * (Optional) Write the child flow's entire {@code vars} map as an object
     * under this parent var key after the subflow completes.
     */
    private String outputVariable;

    /**
     * (Optional) Fine-grained output merge: parent var key → AviatorScript
     * expression evaluated in the <em>child</em> context after it completes.
     * <pre>{ "parentKey": "{{ steps.lastNode.someField }}" }</pre>
     */
    private Map<String, String> outputMapping;
}
