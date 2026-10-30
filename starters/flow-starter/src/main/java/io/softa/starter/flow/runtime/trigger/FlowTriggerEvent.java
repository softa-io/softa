package io.softa.starter.flow.runtime.trigger;

import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Incoming trigger event that may cause one or more flows to start.
 * <p>
 * The {@code type} field holds the {@code TriggerSource} discriminator string
 * (e.g. {@code "EntityChange"}, {@code "FieldChange"}) so it can be
 * matched against registered flow bundles without deserialising the full
 * TriggerSource sub-type at the event level.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "FlowTriggerEvent")
public class FlowTriggerEvent {

    @NotBlank(message = "type must not be blank")
    @Schema(description = "Trigger type discriminator (EntityChange | FieldChange). Submit, Cron, Api and Subflow "
            + "are refused: a submitted record starts its approval through record submission, a Cron flow starts "
            + "on its own schedule, an Api flow is started explicitly by its id, and a Subflow flow runs only "
            + "from a parent flow")
    private String type;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "Identity of the event (a change log's uuid); with the flow, an event starts a run at "
            + "most once, so a redelivered event does not run the flow again", accessMode = Schema.AccessMode.READ_ONLY)
    private String eventId;

    @Schema(description = "Source model when the trigger is entity-related")
    private String sourceModel;

    @Schema(description = "Source row id when the trigger is entity-related")
    private String sourceRowId;

    @Schema(description = "Parameters passed to the flow as initial variables")
    private Map<String, Object> parameters;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "Actor who triggered the event (user id)", accessMode = Schema.AccessMode.READ_ONLY)
    private String actorId;

    /**
     * The variables a flow this event starts begins with, and what its trigger's condition reads: the parameters,
     * with the event's own {@code _triggerType}, {@code _sourceModel} and {@code _sourceRowId}.
     */
    public Map<String, Object> startVariables() {
        Map<String, Object> variables = new LinkedHashMap<>();
        if (parameters != null) {
            variables.putAll(parameters);
        }
        variables.put("_triggerType", type);
        variables.put("_sourceModel", sourceModel);
        variables.put("_sourceRowId", sourceRowId);
        return variables;
    }
}
