package io.softa.starter.file.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.softa.starter.file.entity.ImportTemplate;

/**
 * The order every list of import templates is shown in: {@code sequence} ascending with an unset one
 * last, then id — so the import dialog and the export dialog's "for re-import" group show a model's
 * templates in the same order, on every database.
 */
public final class ImportTemplateDisplayOrder {

    /** Sequence ascending with unset last, then id. Sorted here: databases disagree on where nulls go. */
    public static final Comparator<ImportTemplate> DISPLAY_ORDER =
            Comparator.comparing(ImportTemplate::getSequence, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ImportTemplate::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private ImportTemplateDisplayOrder() {
    }

    /** A sorted copy of {@code templates}. */
    public static List<ImportTemplate> inDisplayOrder(List<ImportTemplate> templates) {
        List<ImportTemplate> sorted = new ArrayList<>(templates);
        sorted.sort(DISPLAY_ORDER);
        return sorted;
    }
}
