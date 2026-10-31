package io.softa.starter.flow.service.support;

import java.util.Collection;
import java.util.Objects;
import org.springframework.util.StringUtils;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;

/**
 * A document's approval flows are told apart by their submit conditions: a submission goes to the one whose
 * condition holds, and a record two of them take cannot be submitted. Two enabled approval flows of one document with
 * the same condition — neither having one, or the very same rule or expression — take exactly the same records,
 * which is what a copied approval flow does until its condition is changed. That much is refused when such a flow is
 * published while enabled, enabled, or rolled back to another revision; conditions that merely overlap show only when
 * a record is submitted or previewed.
 */
public final class SubmitConditions {

    private SubmitConditions() {
    }

    /**
     * Refuses {@code candidate}, the revision flow {@code flowId} is to run, when another enabled approval flow of its
     * document has the same submit condition.
     *
     * @param enabledFlows the effective revisions of the enabled flows of the candidate's document
     * @throws FlowActionValidationException naming the flow that takes the same records
     */
    public static void requireDistinct(String flowId, CompiledFlowDefinition candidate,
                                       Collection<CompiledFlowDefinition> enabledFlows) {
        if (!(candidate.getTrigger() instanceof SubmitTrigger ours)) {
            return;
        }
        for (CompiledFlowDefinition other : enabledFlows) {
            if (Objects.equals(flowId, other.getFlowId()) || !(other.getTrigger() instanceof SubmitTrigger theirs)
                    || !Objects.equals(ours.modelName(), theirs.modelName()) || !sameCondition(ours, theirs)) {
                continue;
            }
            throw new FlowActionValidationException("Flow '" + candidate.getFlowName() + "' would take the same "
                    + ours.modelName() + " records as '" + other.getFlowName() + "': "
                    + (expression(ours) == null && rule(ours) == null
                            ? "neither has a submit condition"
                            : "their submit conditions are the same")
                    + ". Give it a condition of its own, or disable '" + other.getFlowName() + "'.");
        }
    }

    private static boolean sameCondition(SubmitTrigger a, SubmitTrigger b) {
        return Objects.equals(expression(a), expression(b)) && Objects.equals(rule(a), rule(b));
    }

    private static String expression(SubmitTrigger trigger) {
        return StringUtils.hasText(trigger.conditionExpression()) ? trigger.conditionExpression().trim() : null;
    }

    private static String rule(SubmitTrigger trigger) {
        return Filters.isEmpty(trigger.condition()) ? null : JsonUtils.objectToString(trigger.condition());
    }
}
