package io.softa.framework.orm.meta;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

import io.softa.framework.base.exception.JSONException;
import io.softa.framework.orm.domain.Filters;

/**
 * The value of {@code FieldConstraints.requiredWhen}: either a {@link Filters} condition over the
 * row, or {@link #ALWAYS} — the JSON literal {@code true}.
 *
 * <p>{@code ALWAYS} exists for one case the two static flags cannot express: a field that must be
 * filled in at the application level while its column stays nullable ({@code required = true} renders
 * {@code NOT NULL}, and a condition language has no tautology). {@code hiddenWhen} / {@code readonlyWhen}
 * / {@code invalidWhen} do not take it — an unconditional hidden or readonly is the plain flag, and an
 * unconditionally invalid field is a mistake — which is why they are typed as {@link Filters} and only
 * {@code requiredWhen} as this.
 */
@JsonSerialize(using = FieldCondition.Serializer.class)
@JsonDeserialize(using = FieldCondition.Deserializer.class)
public final class FieldCondition implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The annotation / JSON spelling of the unconditional form. */
    public static final String ALWAYS_LITERAL = "true";

    /** Holds for every row. */
    public static final FieldCondition ALWAYS = new FieldCondition(null);

    private final @Nullable Filters filters;

    private FieldCondition(@Nullable Filters filters) {
        this.filters = filters;
    }

    /** A conditional form; the filters must not be empty (an empty condition would mean "always"). */
    public static FieldCondition of(Filters filters) {
        if (Filters.isEmpty(filters)) {
            throw new IllegalArgumentException(
                    "A field condition needs a non-empty filter; write \"true\" for the unconditional form.");
        }
        return new FieldCondition(filters);
    }

    /**
     * Parse the annotation spelling: blank → null (no condition), {@code "true"} → {@link #ALWAYS},
     * anything else → a {@link Filters} expression (structured or semantic).
     */
    public static @Nullable FieldCondition parse(@Nullable String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        if (ALWAYS_LITERAL.equalsIgnoreCase(text.trim())) {
            return ALWAYS;
        }
        return of(Filters.of(text));
    }

    public boolean isAlways() {
        return filters == null;
    }

    /** The condition, or null for {@link #ALWAYS}. */
    public @Nullable Filters getFilters() {
        return filters;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FieldCondition other && Objects.equals(filters, other.filters);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(filters);
    }

    /** The JSON form: {@code true}, or the structured filter array. */
    @Override
    public String toString() {
        return filters == null ? ALWAYS_LITERAL : filters.toString();
    }

    /** Writes {@code true} or the filter array — the same text {@link #toString()} gives. */
    public static final class Serializer extends ValueSerializer<FieldCondition> {
        @Override
        public void serialize(FieldCondition value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
            if (value.isAlways()) {
                gen.writeBoolean(true);
            } else {
                gen.writeRawValue(value.filters.toString());
            }
        }
    }

    /** Reads {@code true}, a filter array, or the string form of either; {@code false} / null → null. */
    public static final class Deserializer extends ValueDeserializer<FieldCondition> {
        @Override
        public @Nullable FieldCondition deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_TRUE) {
                return ALWAYS;
            }
            if (token == JsonToken.VALUE_FALSE || token == JsonToken.VALUE_NULL) {
                return null;
            }
            if (token == JsonToken.VALUE_STRING) {
                return parse(p.readValueAs(String.class));
            }
            if (token == JsonToken.START_ARRAY) {
                List<Object> list = p.readValueAs(new TypeReference<ArrayList<Object>>() {});
                Filters filters = Filters.of(list);
                return Filters.isEmpty(filters) ? null : of(filters);
            }
            throw new JSONException("The value does not deserialize into a field condition: {0}", p.readValueAs(Object.class));
        }
    }
}
