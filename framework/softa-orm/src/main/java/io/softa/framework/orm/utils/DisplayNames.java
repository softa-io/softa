package io.softa.framework.orm.utils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.framework.orm.vo.OptionReference;

/**
 * A record's display name: the values of its model's display fields, as text, joined with
 * {@link StringConstant#DISPLAY_NAME_SEPARATOR}, blank ones left out.
 *
 * <p>A display field may itself be a relation or an option. Read for display it is already text; read as
 * references it is a {@link ModelReference} or an {@link OptionReference}, and many-valued fields are lists of
 * them. Each is turned into what a person reads — a reference's display name (its id when it has none), an
 * option's label (its code when it has none), a list joined with ", " — so a name never shows an object's
 * {@code toString}: a salary item's version used to read "ModelReference(id=891754571066507286,
 * displayName=Basic Monthly Salary) / 2026-10-01".
 */
public final class DisplayNames {

    private static final String LIST_SEPARATOR = ", ";

    private DisplayNames() {
    }

    /**
     * The display name of a row.
     *
     * @param row           the row, holding at least its display fields
     * @param displayFields its model's display fields, in order
     * @return the name; empty when every display field is blank
     */
    public static String of(Map<String, Object> row, List<String> displayFields) {
        return displayFields.stream()
                .map(field -> textOf(row.get(field)))
                .filter(DisplayNames::hasText)
                .collect(Collectors.joining(StringConstant.DISPLAY_NAME_SEPARATOR));
    }

    /** One display field's value as text, or null when it is blank. */
    static String textOf(Object value) {
        return switch (value) {
            case null -> null;
            case ModelReference reference -> hasText(reference.getDisplayName())
                    ? reference.getDisplayName()
                    : Objects.toString(reference.getId(), null);
            case OptionReference option -> hasText(option.getLabel()) ? option.getLabel() : option.getItemCode();
            case Map<?, ?> reference -> textOfMap(reference);
            case Collection<?> values -> {
                String joined = values.stream()
                        .map(DisplayNames::textOf)
                        .filter(DisplayNames::hasText)
                        .collect(Collectors.joining(LIST_SEPARATOR));
                yield joined.isEmpty() ? null : joined;
            }
            default -> value.toString();
        };
    }

    /** A reference read as a map: its display name or label, else its id. */
    private static String textOfMap(Map<?, ?> reference) {
        for (String key : List.of("displayName", "label")) {
            Object text = reference.get(key);
            if (text != null && hasText(text.toString())) {
                return text.toString();
            }
        }
        return Objects.toString(reference.get("id"), null);
    }

    private static boolean hasText(String text) {
        return text != null && !text.isEmpty();
    }
}
