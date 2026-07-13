package io.softa.starter.flow.design.trigger;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Entity change event types that can fire an {@link EntityChangeTrigger}.
 */
@Getter
@AllArgsConstructor
public enum ChangeEvent {
    CREATE("Create"),
    UPDATE("Update"),
    DELETE("Delete");

    @JsonValue
    private final String type;
}
