package io.softa.starter.flow.runtime.trigger;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Incoming trigger event that may cause one or more flows to start.
 * <p>
 * The {@code type} field holds the {@code TriggerSource} discriminator string
 * (an event starts only {@code "EntityChange"} flows) so it can be
 * matched against registered flow bundles without deserialising the full
 * TriggerSource sub-type at the event level.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FlowTriggerEvent {

    /** Trigger type discriminator: {@code EntityChange} for a change log, {@code Cron} for a schedule's tick. */
    private String type;

    /**
     * Identity of the event (a change log's uuid); with the flow, an event starts a run at most once, so a
     * redelivered event does not run the flow again.
     */
    private String eventId;

    /** Source model when the trigger is entity-related. */
    private String sourceModel;

    /** Source row id when the trigger is entity-related. */
    private String sourceRowId;

    /** Parameters passed to the flow as initial variables. */
    private Map<String, Object> parameters;

    /** Actor who triggered the event (user id). */
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
