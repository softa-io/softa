package io.softa.starter.flow.runtime.nodeconfig;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.softa.starter.flow.enums.ApprovalTimeoutStrategy;

/**
 * Config for {@code FlowNodeType.HUMAN_TASK} nodes.
 * <p>
 * A HumanTask is a simple "someone fills a form and clicks submit" step — it has
 * no voting semantics, no multi-approver logic, and no reject/return paths.
 * The assignee completes the task and the flow continues.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HumanTaskNodeConfig {

    /**
     * AviatorScript expression that resolves to the actor id (string) or list of
     * actor ids who can complete this task.
     */
    private String assigneeExpression;

    /**
     * Form code to render for this task (optional).
     * If set, the assigned actor fills this form before submitting.
     */
    private String formCode;

    /** Timeout strategy if the task is not completed in time. */
    @Builder.Default
    private ApprovalTimeoutStrategy timeoutStrategy = ApprovalTimeoutStrategy.REMIND;

    /** Seconds before the timeout strategy is triggered (0 = no timeout). */
    @Builder.Default
    private long timeoutSeconds = 0;

    /** Variable name under which the submitted form data is stored in {@code vars}. */
    private String outputVariable;
}
