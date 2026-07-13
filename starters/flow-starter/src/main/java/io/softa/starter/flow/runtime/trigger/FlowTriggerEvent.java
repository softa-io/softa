package io.softa.starter.flow.runtime.trigger;

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
 * (e.g. {@code "EntityChange"}, {@code "Api"}, {@code "Cron"}) so it can be
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
    @Schema(description = "Trigger type discriminator (EntityChange | Api | Cron | Subflow | FieldChange)")
    private String type;

    @Schema(description = "Source model when the trigger is entity-related")
    private String sourceModel;

    @Schema(description = "Source row id when the trigger is entity-related")
    private String sourceRowId;

    @Schema(description = "Parameters passed to the flow as initial variables")
    private Map<String, Object> parameters;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Schema(description = "Actor who triggered the event (user id)", accessMode = Schema.AccessMode.READ_ONLY)
    private String actorId;
}
