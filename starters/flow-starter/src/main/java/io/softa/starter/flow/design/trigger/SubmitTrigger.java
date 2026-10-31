package io.softa.starter.flow.design.trigger;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Trigger fired when a record of the flow's model is submitted for approval: the trigger of every Approval flow,
 * and of no other. A document has one approval flow — its enabled one is where a submission goes — so the trigger
 * names nothing of its own; the model is the flow's ({@code DesignFlowDefinition#getModelName()}), and differences
 * between records are routes inside the flow. Only the record-submission path ({@code FlowLaunchService.submitRecord})
 * fires it: change logs never produce it, and the generic trigger endpoint refuses it.
 */
@Schema(name = "SubmitTrigger")
public record SubmitTrigger() implements TriggerSource {

    @Override
    public boolean onRecord() {
        return true;
    }
}
