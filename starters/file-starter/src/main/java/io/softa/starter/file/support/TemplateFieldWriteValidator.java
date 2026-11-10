package io.softa.starter.file.support;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.PatchType;
import io.softa.framework.orm.meta.FieldPathUse;
import io.softa.framework.orm.meta.FieldPaths;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.entity.ExportTemplateField;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.entity.ImportTemplateField;
import io.softa.starter.file.excel.imports.ImportFieldPaths;

/**
 * An import or export template is checked against the model it names when it is saved: the model exists,
 * and every field it names can serve what the template uses it for — an export's filters in a WHERE, its
 * orders in an ORDER BY, its columns as reads, an import's unique key in a WHERE and its columns by the
 * importer's own rules.
 *
 * <p>Checked only when the template runs, a wrong field fails the whole export or import on whoever runs
 * it, long after the template was saved — by a seed, by the API, or by an editor whose model was changed
 * afterwards. The rules are the run's own ({@link FieldPaths}, {@link ImportFieldPaths}), so a template
 * that saves does not fail later on a field.
 *
 * <p>A field is checked when the write sets it, and when the template moves to another model — which
 * also re-checks the columns the write leaves as they are. An import template's columns are also checked
 * together whenever a write changes them ({@link ImportFieldPaths#checkColumns}), as the template will hold
 * them once the write is done. A template already broken by a metadata change can still be edited elsewhere;
 * the fields that are wrong are named when they are next set.
 */
@Component
@Order(150)
public class TemplateFieldWriteValidator implements ModelWriteValidator {

    private static final String EXPORT_TEMPLATE = ExportTemplate.class.getSimpleName();
    private static final String EXPORT_TEMPLATE_FIELD = ExportTemplateField.class.getSimpleName();
    private static final String IMPORT_TEMPLATE = ImportTemplate.class.getSimpleName();
    private static final String IMPORT_TEMPLATE_FIELD = ImportTemplateField.class.getSimpleName();
    private static final Set<String> MODELS =
            Set.of(EXPORT_TEMPLATE, EXPORT_TEMPLATE_FIELD, IMPORT_TEMPLATE, IMPORT_TEMPLATE_FIELD);

    private static final String MODEL_NAME = LambdaUtils.getAttributeName(ExportTemplate::getModelName);
    private static final String FILTERS = LambdaUtils.getAttributeName(ExportTemplate::getFilters);
    private static final String ORDERS = LambdaUtils.getAttributeName(ExportTemplate::getOrders);
    private static final String EXPORT_FIELDS = LambdaUtils.getAttributeName(ExportTemplate::getExportFields);
    private static final String UNIQUE_CONSTRAINTS = LambdaUtils.getAttributeName(ImportTemplate::getUniqueConstraints);
    private static final String IMPORT_FIELDS = LambdaUtils.getAttributeName(ImportTemplate::getImportFields);
    private static final String TEMPLATE_ID = LambdaUtils.getAttributeName(ExportTemplateField::getTemplateId);
    private static final String FIELD_NAME = LambdaUtils.getAttributeName(ExportTemplateField::getFieldName);

    private final ModelService<?> modelService;

    public TemplateFieldWriteValidator(ModelService<?> modelService) {
        this.modelService = modelService;
    }

    @Override
    public boolean supports(String modelName) {
        return MODELS.contains(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        validate(ctx, true);
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        validate(ctx, false);
    }

    private void validate(WriteContext ctx, boolean create) {
        String model = ctx.modelName();
        if (EXPORT_TEMPLATE.equals(model)) {
            checkExportTemplate(ctx, create);
        } else if (IMPORT_TEMPLATE.equals(model)) {
            checkImportTemplate(ctx, create);
        } else if (create || ctx.patch().containsKey(FIELD_NAME) || ctx.patch().containsKey(TEMPLATE_ID)) {
            checkColumn(ctx);
        }
    }

    /** An export template's filters and orders, and — when it moves to another model — its columns. */
    private void checkExportTemplate(WriteContext ctx, boolean create) {
        boolean modelChanged = create || changes(ctx, MODEL_NAME);
        boolean filtersSet = modelChanged || ctx.patch().containsKey(FILTERS);
        boolean ordersSet = modelChanged || ctx.patch().containsKey(ORDERS);
        if (!filtersSet && !ordersSet) {
            return;
        }
        String model = existingModel(ctx);
        if (model == null) {
            return;
        }
        if (filtersSet) {
            FieldPaths.filterProblems(model, toFilters(ctx.row().get(FILTERS)))
                    .forEach(problem -> ctx.reject(FILTERS, problem));
        }
        if (ordersSet) {
            FieldPaths.orderProblems(model, toOrders(ctx.row().get(ORDERS)))
                    .forEach(problem -> ctx.reject(ORDERS, problem));
        }
        if (modelChanged && !create) {
            checkKeptColumns(ctx, EXPORT_TEMPLATE_FIELD, EXPORT_FIELDS,
                    path -> FieldPaths.problem(model, path, FieldPathUse.READ));
        }
    }

    /**
     * An import template's columns taken together when the write changes them, its unique key, and — when it moves
     * to another model — its columns.
     */
    private void checkImportTemplate(WriteContext ctx, boolean create) {
        if (ctx.patch().containsKey(IMPORT_FIELDS)) {
            checkColumnsTogether(ctx, create);
        }
        boolean modelChanged = create || changes(ctx, MODEL_NAME);
        if (!modelChanged && !ctx.patch().containsKey(UNIQUE_CONSTRAINTS)) {
            return;
        }
        String model = existingModel(ctx);
        if (model == null) {
            return;
        }
        // The unique key is looked up in the target table — a WHERE on those fields.
        for (String field : toFieldList(ctx.row().get(UNIQUE_CONSTRAINTS))) {
            String problem = FieldPaths.problem(model, field, FieldPathUse.FILTER);
            if (problem != null) {
                ctx.reject(UNIQUE_CONSTRAINTS, FieldPaths.named(field, problem));
            }
        }
        if (modelChanged && !create) {
            checkKeptColumns(ctx, IMPORT_TEMPLATE_FIELD, IMPORT_FIELDS, path -> ImportFieldPaths.problem(model, path));
        }
    }

    /**
     * The template's columns as the write leaves them, checked together by the importer's own rules: what the
     * columns are now, with the write's list or patch laid on top. Here, before any column is written, because a
     * template's columns are then created, updated and deleted one batch after another — taken one at a time they
     * may pass through a combination that neither the old columns nor the new ones are, such as a relation
     * imported directly while it is being turned into a lookup. A column written on its own, not through its
     * template, is checked alone; the import checks it with the rest when it runs.
     */
    private void checkColumnsTogether(WriteContext ctx, boolean create) {
        Object written = ctx.patch().get(IMPORT_FIELDS);
        List<String> paths = create ? createdPaths(written) : columnsAfter(ctx.id(), written);
        String problem = ImportFieldPaths.columnsProblem(paths);
        if (problem != null) {
            ctx.reject(IMPORT_FIELDS, problem);
        }
    }

    /** The paths of the columns a new template is created with: a list of them, or the {@code Create} rows of a patch. */
    private static List<String> createdPaths(@Nullable Object written) {
        List<String> paths = new ArrayList<>();
        if (written instanceof Collection<?> rows) {
            rows.forEach(row -> addPath(paths, row));
        } else if (written instanceof Map<?, ?> operations) {
            operations.forEach((type, rows) -> {
                if (PatchType.CREATE.getType().equalsIgnoreCase(String.valueOf(type)) && rows instanceof Collection<?> created) {
                    created.forEach(row -> addPath(paths, row));
                }
            });
        }
        return paths;
    }

    /**
     * The paths of a stored template's columns once the write is done. A list restates them: a row with an id keeps
     * or changes that column, one without is new, a column not listed goes. A patch changes them: its {@code Delete}
     * ids go, its {@code Update} rows change a column's path when they set one, its {@code Create} rows are new. A
     * null clears them.
     */
    private List<String> columnsAfter(@Nullable Serializable templateId, @Nullable Object written) {
        if (written == null) {
            return List.of();
        }
        Map<String, @Nullable String> stored = new LinkedHashMap<>();
        pastRowScope(() -> modelService.searchList(IMPORT_TEMPLATE_FIELD,
                new FlexQuery(List.of(ModelConstant.ID, FIELD_NAME), new Filters().eq(TEMPLATE_ID, templateId))))
                .forEach(column -> stored.put(String.valueOf(column.get(ModelConstant.ID)), text(column.get(FIELD_NAME))));
        List<String> paths = new ArrayList<>();
        if (written instanceof Collection<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> column) {
                    String path = text(column.get(FIELD_NAME));
                    Object id = column.get(ModelConstant.ID);
                    addPath(paths, path != null ? path : id == null ? null : stored.get(String.valueOf(id)));
                }
            }
            return paths;
        }
        if (!(written instanceof Map<?, ?> operations)) {
            // Not a list or a patch at all: the field's own processing says so.
            return List.of();
        }
        Set<String> deleted = new HashSet<>();
        Map<String, String> repathed = new HashMap<>();
        List<String> created = new ArrayList<>();
        operations.forEach((type, rows) -> {
            String operation = String.valueOf(type);
            if (!(rows instanceof Collection<?> entries)) {
                return;
            }
            if (PatchType.DELETE.getType().equalsIgnoreCase(operation)) {
                entries.forEach(id -> deleted.add(String.valueOf(id)));
            } else if (PatchType.UPDATE.getType().equalsIgnoreCase(operation)) {
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> column && text(column.get(FIELD_NAME)) != null) {
                        repathed.put(String.valueOf(column.get(ModelConstant.ID)), text(column.get(FIELD_NAME)));
                    }
                }
            } else if (PatchType.CREATE.getType().equalsIgnoreCase(operation)) {
                entries.forEach(row -> addPath(created, row));
            }
        });
        stored.forEach((id, path) -> {
            if (!deleted.contains(id)) {
                addPath(paths, repathed.getOrDefault(id, path));
            }
        });
        paths.addAll(created);
        return paths;
    }

    /** Adds the path a column row — or a path itself — names, when it names one. */
    private static void addPath(List<String> paths, @Nullable Object column) {
        String path = column instanceof Map<?, ?> row ? text(row.get(FIELD_NAME)) : text(column);
        if (path != null) {
            paths.add(path);
        }
    }

    /** One column, against the model of the template it belongs to. */
    private void checkColumn(WriteContext ctx) {
        String path = text(ctx.row().get(FIELD_NAME));
        if (path == null) {
            // Required: the pipeline says so.
            return;
        }
        boolean export = EXPORT_TEMPLATE_FIELD.equals(ctx.modelName());
        String model = templateModel(ctx, export ? EXPORT_TEMPLATE : IMPORT_TEMPLATE, ctx.row().get(TEMPLATE_ID));
        if (model == null || !ModelManager.existModel(model)) {
            // The template's own check names a model that does not exist.
            return;
        }
        String problem = export ? FieldPaths.problem(model, path, FieldPathUse.READ) : ImportFieldPaths.problem(model, path);
        if (problem != null) {
            ctx.reject(FIELD_NAME, FieldPaths.named(path, problem));
        }
    }

    /**
     * The columns a template keeps when it moves to another model, checked against the new one. A write that
     * restates the columns as a list writes every one of them, and each is checked as it is written; one that
     * patches them leaves the columns it does not update or delete as they are, and those are checked here.
     */
    private void checkKeptColumns(WriteContext ctx, String columnModel, String columnsField,
                                  Function<String, @Nullable String> problemOf) {
        Object written = ctx.patch().get(columnsField);
        if (written instanceof Collection<?>) {
            return;
        }
        Set<String> rewritten = rewrittenColumnIds(written);
        List<Map<String, Object>> columns = pastRowScope(() -> modelService.searchList(columnModel,
                new FlexQuery(List.of(ModelConstant.ID, FIELD_NAME), new Filters().eq(TEMPLATE_ID, ctx.id()))));
        for (Map<String, Object> column : columns) {
            String path = text(column.get(FIELD_NAME));
            if (path == null || rewritten.contains(String.valueOf(column.get(ModelConstant.ID)))) {
                continue;
            }
            String problem = problemOf.apply(path);
            if (problem != null) {
                ctx.reject(columnsField, FieldPaths.named(path, problem));
            }
        }
    }

    /** The columns a one-to-many patch deletes, or updates with a field of their own — checked when written. */
    private static Set<String> rewrittenColumnIds(@Nullable Object patch) {
        Set<String> ids = new HashSet<>();
        if (!(patch instanceof Map<?, ?> operations)) {
            return ids;
        }
        operations.forEach((type, value) -> {
            String operation = String.valueOf(type);
            if (!(value instanceof Collection<?> entries)) {
                return;
            }
            if (PatchType.DELETE.getType().equalsIgnoreCase(operation)) {
                entries.forEach(id -> ids.add(String.valueOf(id)));
            } else if (PatchType.UPDATE.getType().equalsIgnoreCase(operation)) {
                entries.stream()
                        .filter(entry -> entry instanceof Map<?, ?> row && row.containsKey(FIELD_NAME))
                        .forEach(entry -> ids.add(String.valueOf(((Map<?, ?>) entry).get(ModelConstant.ID))));
            }
        });
        return ids;
    }

    /** The template's model when it exists; otherwise rejects it — a blank one is the pipeline's to report. */
    private static @Nullable String existingModel(WriteContext ctx) {
        String model = text(ctx.row().get(MODEL_NAME));
        if (model == null) {
            return null;
        }
        if (!ModelManager.existModel(model)) {
            ctx.reject(MODEL_NAME, "Model '" + model + "' does not exist.");
            return null;
        }
        return model;
    }

    /**
     * The model of the template a column belongs to, read past the caller's row scope — a template is
     * configuration, not the caller's data, as the import and the export read it. A column written through
     * its template is written after the template itself, so this reads the model the template now names.
     */
    private @Nullable String templateModel(WriteContext ctx, String templateModel, @Nullable Object templateId) {
        if (templateId == null) {
            return null;
        }
        String key = templateModel + ":" + templateId;
        Map<String, Object> scratch = ctx.scratch();
        if (!scratch.containsKey(key)) {
            List<Map<String, Object>> rows = pastRowScope(() -> modelService.searchList(templateModel,
                    new FlexQuery(List.of(ModelConstant.ID, MODEL_NAME), new Filters().eq(ModelConstant.ID, templateId))));
            scratch.put(key, rows.isEmpty() ? null : rows.getFirst().get(MODEL_NAME));
        }
        return text(scratch.get(key));
    }

    /**
     * A read past the caller's row scope, inside the tenant — how the import and the export read a template:
     * which columns a template has is configuration, not the caller's data.
     */
    private static <T> T pastRowScope(Supplier<T> read) {
        Context context = ContextHolder.cloneContext();
        context.setSkipPermissionCheck(true);
        return ContextHolder.callWith(context, read::get);
    }

    /** Whether the patch writes this field to a value other than the stored one. */
    private static boolean changes(WriteContext ctx, String field) {
        if (!ctx.patch().containsKey(field)) {
            return false;
        }
        Object stored = ctx.originalRow() == null ? null : ctx.originalRow().get(field);
        return !Objects.equals(text(ctx.patch().get(field)), text(stored));
    }

    /** A condition as the request sent it — the object, its JSON, or its list form; null when there is none. */
    private static @Nullable Filters toFilters(@Nullable Object raw) {
        try {
            return switch (raw) {
                case null -> null;
                case Filters filters -> filters;
                case String json when json.isBlank() -> null;
                case String json -> Filters.of(json);
                case List<?> list -> Filters.of(list);
                default -> null;
            };
        } catch (RuntimeException e) {
            // Not a condition at all: the field's own processing says so.
            return null;
        }
    }

    /** An order as the request sent it — the object, {@code name ASC, code DESC}, or its list form. */
    private static @Nullable Orders toOrders(@Nullable Object raw) {
        try {
            return switch (raw) {
                case null -> null;
                case Orders orders -> orders;
                case String text when text.isBlank() -> null;
                case String json when json.trim().startsWith("[") -> Orders.of(Cast.<List<Object>>of(
                        JsonUtils.stringToObject(json, List.class)));
                case String text -> Orders.of(text);
                case List<?> list -> Orders.of(Cast.<List<Object>>of(list));
                default -> null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A list of field names as the request sent it — a list, or one comma-separated string. */
    private static List<String> toFieldList(@Nullable Object raw) {
        List<String> fields = new ArrayList<>();
        if (raw instanceof Collection<?> values) {
            values.forEach(value -> fields.add(String.valueOf(value)));
        } else if (raw instanceof String text) {
            fields.addAll(List.of(StringUtils.split(text, ",")));
        }
        return fields.stream().map(String::trim).filter(StringUtils::isNotEmpty).toList();
    }

    private static @Nullable String text(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
