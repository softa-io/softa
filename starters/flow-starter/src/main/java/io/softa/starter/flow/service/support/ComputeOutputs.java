package io.softa.starter.flow.service.support;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.util.StringUtils;

import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;

/**
 * The Compute flows of a model answer one form change together: the outputs each declares are merged into the
 * form, so two of them declaring the same output would overwrite each other, whichever ran last winning. Among a
 * model's enabled Compute flows an output therefore has one author — checked when a Compute flow is published while
 * enabled, enabled, or rolled back to another revision.
 */
public final class ComputeOutputs {

    private ComputeOutputs() {
    }

    /**
     * Refuses {@code candidate}, the revision flow {@code flowId} is to run, when it declares an output another
     * enabled Compute flow of its model declares.
     *
     * @param enabledFlows the effective revisions of the enabled flows of the candidate's model
     * @throws FlowActionValidationException naming the outputs and the flow that declares them
     */
    public static void requireOwn(String flowId, CompiledFlowDefinition candidate,
                                  Collection<CompiledFlowDefinition> enabledFlows) {
        if (!FlowScenario.COMPUTE.equals(candidate.getScenario()) || !StringUtils.hasText(candidate.getModelName())
                || candidate.getDeclaredOutputs() == null || candidate.getDeclaredOutputs().isEmpty()) {
            return;
        }
        for (CompiledFlowDefinition other : enabledFlows) {
            if (Objects.equals(flowId, other.getFlowId()) || !FlowScenario.COMPUTE.equals(other.getScenario())
                    || !candidate.getModelName().equals(other.getModelName()) || other.getDeclaredOutputs() == null) {
                continue;
            }
            List<String> shared = candidate.getDeclaredOutputs().stream()
                    .filter(other.getDeclaredOutputs()::contains)
                    .distinct()
                    .toList();
            if (!shared.isEmpty()) {
                throw new FlowActionValidationException("Flow '" + candidate.getFlowName() + "' computes "
                        + String.join(", ", shared) + ", which '" + other.getFlowName() + "' already computes for "
                        + candidate.getModelName() + ": the two would overwrite each other. Keep each output in one"
                        + " Compute flow, or disable '" + other.getFlowName() + "'.");
            }
        }
    }
}
