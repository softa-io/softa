package io.softa.framework.orm.enums;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.orm.annotation.OptionItem;
import io.softa.framework.orm.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * Widget type Enum.
 * Used to define the data rendering mode in the frontend view.
 */
@Getter
@AllArgsConstructor
@OptionSet(label = "Widget Type")
public enum WidgetType {
    // String
    @OptionItem(label = "URL")
    URL("URL"),
    PHONE("Phone"),
    EMAIL("Email"),
    TEXT("Text"),
    RICH_TEXT("RichText"),
    TEMPLATE_EDITOR("TemplateEditor"),
    MARKDOWN("Markdown"),
    CODE("Code"),
    @OptionItem(label = "Color picker")
    COLOR("Color"),

    // Numeric
    MONETARY("Monetary"),
    PERCENTAGE("Percentage"),
    SLIDER("Slider"),

    // Option fields
    RADIO("Radio"),
    // Boolean, MultiOption fields
    @OptionItem(label = "CheckBox")
    CHECK_BOX("CheckBox"),

    // Option, OneToMany fields
    @OptionItem(label = "Status bar")
    STATUS_BAR("StatusBar"),

    // OneToMany, ManyToMany fields

    // Single attachment, file key stored in a String field
    @OptionItem(label = "Single Image")
    IMAGE("Image"),

    // Multiple attachments, OneToMany, ManyToMany fields
    @OptionItem(label = "Multiple Images")
    MULTI_IMAGE("MultiImage"),

    // Date, DateTime use the corresponding default widget
    @OptionItem(label = "Year-Month picker")
    YYYY_MM("yyyy-MM"),
    @OptionItem(label = "Month-Day picker")
    MM_DD("MM-dd"),
    @OptionItem(label = "Hour-Minute picker")
    HH_MM("HH:mm"),
    @OptionItem(label = "Time picker")
    HH_MM_SS("HH:mm:ss"),
    @OptionItem(label = "Relative time")
    RELATIVE("Relative"),

    // Tree structure, such as org structure, category, etc.
    @OptionItem(label = "Tree Select")
    SELECT_TREE("SelectTree"),

    // JSON tree to display JSON values in the tree view
    JSON_TREE("JsonTree"),

    TAG_LIST("TagList"),

    @OptionItem(label = "Cron expression editor")
    CRON_EDITOR("CronEditor")
    ;

    @JsonValue
    private final String name;

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
