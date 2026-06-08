package io.softa.framework.orm.enums;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.annotation.OptionSet;

/**
 * View type Enum.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "View Type")
public enum ViewType {
    TABLE("Table"),
    FORM("Form"),
    CARD("Card"),
    KANBAN("Kanban"),
    CALENDAR("Calendar"),
    DASHBOARD("Dashboard"),
    ;

    @JsonValue
    private final String type;

    /** type map */
    static private final Map<String, ViewType> TYPE_MAP = Stream.of(values()).collect(Collectors.toMap(ViewType::getType, Function.identity()));

    /**
     * Get ViewType by string
     * @param type string
     * @return ViewType
     */
    public static ViewType of(String type) {
        Assert.isTrue(TYPE_MAP.containsKey(type), "{0} not exist in ViewType!", type);
        return TYPE_MAP.get(type);
    }
}
