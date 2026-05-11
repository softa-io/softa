package io.softa.starter.metadata.sequence.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * How often the sequence counter resets back to {@code start_value}.
 * Each cadence corresponds to a {@code current_key} format used both as the
 * reset boundary detector ({@code last_reset_key} comparison) and as the
 * source for date tokens ({@code yyyy} / {@code MM} / {@code dd}) at render
 * time.
 *
 * <ul>
 *   <li>{@link #NONE}    — never reset; current_key = "" (empty string, NOT null)</li>
 *   <li>{@link #YEARLY}  — reset on year boundary; current_key = "yyyy"</li>
 *   <li>{@link #MONTHLY} — reset on month boundary; current_key = "yyyy-MM"</li>
 *   <li>{@link #DAILY}   — reset on day boundary; current_key = "yyyy-MM-dd"</li>
 * </ul>
 *
 * <p>{@code @JsonValue} on {@link #code} keeps JSON serialization symmetric
 * with {@link SequenceMode}: API payloads carry the pretty-case label
 * ({@code "None"} / {@code "Yearly"} / …) and Jackson reverses it back to
 * the enum constant on deserialization.
 */
@Getter
public enum ResetCadence {

    NONE("None", null),
    YEARLY("Yearly", DateTimeFormatter.ofPattern("yyyy")),
    MONTHLY("Monthly", DateTimeFormatter.ofPattern("yyyy-MM")),
    DAILY("Daily", DateTimeFormatter.ofPattern("yyyy-MM-dd")),
    ;

    @JsonValue
    private final String code;

    private final DateTimeFormatter formatter;

    ResetCadence(String code, DateTimeFormatter formatter) {
        this.code = code;
        this.formatter = formatter;
    }

    /**
     * Compute the reset key for the given moment.
     * Used by sequence allocation to compare against {@code last_reset_key}
     * and by template rendering to source date tokens. {@link #NONE} returns
     * the empty string so the SQL {@code <=>} null-safe comparison treats it
     * as a stable boundary.
     */
    public String computeKey(LocalDateTime time) {
        return formatter == null ? "" : time.format(formatter);
    }
}
