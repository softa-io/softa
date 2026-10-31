package io.softa.starter.flow.design.trigger;

import io.swagger.v3.oas.annotations.media.Schema;

import io.softa.framework.orm.domain.Filters;

/**
 * Trigger fired when a record of the document {@code modelName} is submitted for approval: the trigger of every
 * Approval flow, and of no other. A document may have several approval flows — one per country or company, a leave
 * and its cancellation — told apart by their conditions: a submission goes to the document's enabled approval flow
 * whose condition holds for the record (no condition holds for every record), and exactly one must hold. The
 * condition is one of the two kinds an edge takes, never both: a rule over the record's fields, or an expression.
 * The document is the system's, not the designer's: chosen when the flow is created ({@code FlowDesign#getModelName()})
 * and put back on every save, which keeps the condition. Only the record-submission path
 * ({@code FlowLaunchService.submitRecord}) fires it: change logs never produce it, and the generic trigger endpoint
 * refuses it.
 */
@Schema(name = "SubmitTrigger")
public record SubmitTrigger(

        @Schema(description = "The document the flow approves: the model whose records are submitted to it")
        String modelName,

        @Schema(description = "Optional AviatorScript expression over the submitted record's fields; the flow takes "
                + "the records it holds for. Exclusive with condition")
        String conditionExpression,

        @Schema(description = "Optional rule over the submitted record's fields (platform Filters), judged like an "
                + "edge's rule condition; the flow takes the records it holds for. Exclusive with conditionExpression")
        Filters condition

) implements TriggerSource {

    /** A submission of {@code modelName}'s records, all of them. */
    public SubmitTrigger(String modelName) {
        this(modelName, null, null);
    }

    /** A submission of {@code modelName}'s records, those {@code conditionExpression} holds for. */
    public SubmitTrigger(String modelName, String conditionExpression) {
        this(modelName, conditionExpression, null);
    }

    /** The same condition on {@code document}'s records. */
    public SubmitTrigger onDocument(String document) {
        return new SubmitTrigger(document, conditionExpression, condition);
    }

    @Override
    public boolean onRecord() {
        return true;
    }
}
