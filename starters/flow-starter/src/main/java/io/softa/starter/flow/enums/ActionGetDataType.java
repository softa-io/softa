package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * Query mode for the flow engine's "get data" task (P0-6).
 *
 * <p>This enum is the <b>single source of truth</b> for the query modes:
 * {@code GetDataTaskExecutor} derives its routing sets ({@code COUNT_TYPES = {Exist,
 * Count}}, {@code SINGLE_ROW_TYPES = {SingleRow, OneFieldValue}},
 * {@code MULTI_ROW_TYPES = {MultiRows, OneFieldValues}}) and its {@code getDataType}
 * option schema directly from {@link #getCode()}, so adding or renaming a value is a
 * one-line change here. The scanner materializes this option set from the code,
 * so the engine's routing sets and the stored option schema always agree.
 *
 * <p>Labels are omitted: each equals {@code humanize(name())} (e.g.
 * {@code ONE_FIELD_VALUE -> "One Field Value"}), which the parser applies
 * automatically. The persisted {@code itemCode} is the {@link #code}
 * ({@code @JsonValue}), matching the value flow nodes carry.
 */
@Getter
@AllArgsConstructor
@OptionSet   // label omitted: humanize("ActionGetDataType") == "Action Get Data Type"
public enum ActionGetDataType {
    SINGLE_ROW("SingleRow"),
    MULTI_ROWS("MultiRows"),
    ONE_FIELD_VALUE("OneFieldValue"),
    ONE_FIELD_VALUES("OneFieldValues"),
    EXIST("Exist"),
    COUNT("Count"),
    ;

    @JsonValue
    private final String code;
}
