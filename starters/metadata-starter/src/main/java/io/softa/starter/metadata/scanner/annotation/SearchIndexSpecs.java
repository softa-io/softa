package io.softa.starter.metadata.scanner.annotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.softa.framework.orm.enums.StorageType;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;

/**
 * Adapts the annotation lane's catalog rows into {@link SearchIndexSynthesizer}'s neutral input.
 *
 * <p>Separate from the synthesizer so that the studio lane, which holds the same information as
 * {@code design_*} attribute maps rather than {@code Sys*} entities, can supply its own adapter
 * without either side reimplementing the derivation rule.
 */
public final class SearchIndexSpecs {

    private SearchIndexSpecs() {
    }

    /** Group {@code fields} under their owning model and pair each model with its own. */
    public static List<SearchIndexSynthesizer.ModelSpec> from(List<SysModel> models,
                                                              List<SysField> fields) {
        Map<String, List<SearchIndexSynthesizer.FieldSpec>> byModel = new LinkedHashMap<>();
        for (SysField field : fields) {
            byModel.computeIfAbsent(field.getModelName(), k -> new ArrayList<>())
                    .add(new SearchIndexSynthesizer.FieldSpec(
                            field.getFieldName(),
                            field.getColumnName(),
                            field.getFieldType(),
                            Boolean.TRUE.equals(field.getDynamic())));
        }
        List<SearchIndexSynthesizer.ModelSpec> specs = new ArrayList<>(models.size());
        for (SysModel model : models) {
            specs.add(new SearchIndexSynthesizer.ModelSpec(
                    model.getModelName(),
                    model.getTableName(),
                    model.getSearchName(),
                    Boolean.TRUE.equals(model.getProjection()),
                    // Null storageType means "not overridden", which is RDBMS — same reading the
                    // DDL planner uses when it decides whether a model owns a table at all.
                    model.getStorageType() == null || model.getStorageType() == StorageType.RDBMS,
                    byModel.getOrDefault(model.getModelName(), List.of())));
        }
        return specs;
    }
}
