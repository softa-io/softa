package io.softa.starter.flow.design.trigger;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonSubTypes.Type;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface representing the external trigger that starts a new flow instance.
 * <p>
 * Each sub-type carries only the fields that are meaningful for that specific
 * trigger source — eliminating the field cross-contamination present in the old
 * flat {@code FlowTriggerDefinition} model.
 *
 * <p>Jackson uses {@code "type"} as the polymorphic discriminator, matching the
 * string values registered via {@link JsonSubTypes}.
 *
 * @see EntityChangeTrigger
 * @see ApiTrigger
 * @see CronTrigger
 * @see SubflowTrigger
 * @see FieldChangeTrigger
 * @see SubmitTrigger
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @Type(value = EntityChangeTrigger.class, name = "EntityChange"),
        @Type(value = ApiTrigger.class,          name = "Api"),
        @Type(value = CronTrigger.class,         name = "Cron"),
        @Type(value = SubflowTrigger.class,      name = "Subflow"),
        @Type(value = FieldChangeTrigger.class,  name = "FieldChange"),
        @Type(value = SubmitTrigger.class,       name = "Submit"),
})
public sealed interface TriggerSource
        permits EntityChangeTrigger, ApiTrigger, CronTrigger,
                SubflowTrigger, FieldChangeTrigger, SubmitTrigger {

    /**
     * Whether the flow starts on a record of its model ({@code DesignFlowDefinition#getModelName()}) — a submission,
     * a change, a field change — whose fields its variables and route rules read. Such a flow needs its model; one
     * started by a schedule, an API call or a parent flow carries no record, and has none.
     */
    default boolean onRecord() {
        return false;
    }
}
