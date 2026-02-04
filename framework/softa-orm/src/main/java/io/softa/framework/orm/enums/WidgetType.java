package io.softa.framework.orm.enums;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * Widget type Enum.
 * Used to define the data rendering mode in the frontend view.
 */
@Getter
@AllArgsConstructor
public enum WidgetType {
    // String
    URL("URL", "URL"),
    EMAIL("Email", "Email"),
    HTML("Html", "HTML"),
    TEXT("Text", "Text"),
    COLOR("Color", "Color picker"),

    // Numeric
    MONETARY("Monetary", "Monetary"),
    PERCENTAGE("Percentage", "Percentage"),
    SLIDER("Slider", "Slider"),

    // Boolean, Option fields
    RADIO("Radio", "Radio"),

    // Option, OneToMany fields
    STATUS_BAR("StatusBar", "Status bar"),

    // OneToMany, ManyToMany fields
    CHECK_BOX("CheckBox", "CheckBox"),

    // Single attachment, file key stored in a String field
    IMAGE("Image", "Single Image"),

    // Multiple attachments, OneToMany, ManyToMany fields
    MULTI_IMAGE("MultiImage", "Multiple Images"),

    // Date, DateTime use the corresponding default widget
    YYYY_MM("yyyy-MM", "Year-Month picker"),
    MM_DD("MM-dd", "Month-Day picker"),
    HH_MM("HH:mm", "Hour-Minute picker"),
    HH_MM_SS("HH:mm:ss", "Time picker"),
    ;

    @JsonValue
    private final String name;
    private final String description;

    /** names map */
    static private final Map<String, WidgetType> namesMap = Stream.of(values()).collect(Collectors.toMap(WidgetType::getName, Function.identity()));

    /**
     * Get WidgetType by name
     * @param name string
     * @return WidgetType
     */
    public static WidgetType of(String name) {
        return StringUtils.isBlank(name) ? null : namesMap.get(name);
    }
}
