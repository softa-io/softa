package io.softa.framework.orm.service.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Getter;

import io.softa.framework.base.exception.IllegalArgumentException;

/**
 * A write refused by the {@link ModelWriteValidator} chain — a 400, like every other rejected input,
 * carrying the field errors the validators accumulated so a form can put each one on its field.
 *
 * <p>Extends the framework's {@code IllegalArgumentException} so the existing exception mapping
 * applies unchanged; exposing {@link #fieldErrors()} in the response body is the web layer's step.
 */
@Getter
public class WriteValidationException extends IllegalArgumentException {

    /** One rejection: which row of the batch, which field, what sentence. */
    public record FieldError(int rowIndex, String field, String message) {}

    private final transient List<FieldError> errors;

    /** The accumulated form: every rejection, message composed from them. */
    public WriteValidationException(List<FieldError> errors) {
        super(compose(errors));
        this.errors = List.copyOf(errors);
    }

    /** The fail-fast form: one sentence, no field. */
    public WriteValidationException(String message, Object... args) {
        super(message, args);
        this.errors = List.of();
    }

    /**
     * One field, one sentence — what a field constraint throws from the pipeline. With arguments the
     * message is a {@code MessageFormat} pattern ({@code {0}} placeholders) and goes through the
     * framework's formatting and i18n like every other {@code IllegalArgumentException}; with none it
     * is looked up for translation and otherwise shown as written — the form for a sentence a
     * developer declared ({@code constraintMessage}), whose apostrophes and braces are text, not
     * syntax. Either way the sentence is recorded against the field so the body carries it as
     * {@code fieldErrors} too.
     */
    public static WriteValidationException forField(String field, String message, Object... args) {
        WriteValidationException e = new WriteValidationException(message, args);
        return new WriteValidationException(List.of(new FieldError(0, field, e.getMessage())), e.getMessage());
    }

    private WriteValidationException(List<FieldError> errors, String formattedMessage) {
        super(formattedMessage);
        this.errors = List.copyOf(errors);
    }

    /** Field → message, first rejection per field; what an API response would carry as fieldErrors. */
    public Map<String, String> fieldErrors() {
        Map<String, String> map = new LinkedHashMap<>();
        errors.forEach(e -> map.putIfAbsent(e.field(), e.message()));
        return map;
    }

    private static String compose(List<FieldError> errors) {
        boolean multiRow = errors.stream().mapToInt(FieldError::rowIndex).distinct().count() > 1;
        return errors.stream()
                .map(e -> (multiRow ? "row " + (e.rowIndex() + 1) + " " : "") + e.field() + ": " + e.message())
                .collect(Collectors.joining("; "));
    }
}
