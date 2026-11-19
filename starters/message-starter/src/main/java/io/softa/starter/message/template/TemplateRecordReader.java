package io.softa.starter.message.template;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.placeholder.TemplateBlocks;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.vo.ModelReference;
import io.softa.framework.orm.vo.OptionReference;

/**
 * Reads the record a model-bound template is filled from: exactly the fields the template asks for
 * ({@link TemplateUsage}), as the variables it is rendered from.
 *
 * <p><b>What comes back.</b> A map shaped like the template's own names — {@code departmentId.name}
 * is {@code {departmentId: {name: …}}} — holding display values: an option's label, a relation named
 * on its own as its display name. Each loop table's list is a list of such maps, one per row, with
 * the columns the table shows. When the template has condition blocks, the values they compare are
 * read once more as they are stored (an option's code, a relation's id, a real boolean) and placed
 * under {@link TemplateBlocks#RAW_ROOT}.
 *
 * <p><b>Read as the system, unmasked.</b> A template is checked against its author's access when it
 * is saved; who happens to trigger the send must not change what the mail says, so the read sets the
 * caller's row scope, field checks and masking aside. Tenant stays as it is.
 *
 * <p><b>Lenient about fields that are gone.</b> A path the model can no longer answer is left out
 * and prints blank: a field removed after the template was saved must not stop its mails.
 *
 * <p>A field a model copies from a relation is read at its origin ({@code positionId.name} declared
 * as a copy of {@code employeeId.positionId} is read as {@code employeeId.positionId.name}): a copied
 * relation comes back as a bare reference and cannot be followed.
 */
@Component
public class TemplateRecordReader {

    private final ModelService<Serializable> modelService;

    private final TemplateModels models;

    public TemplateRecordReader(@Lazy ModelService<Serializable> modelService, TemplateModels models) {
        this.modelService = modelService;
        this.models = models;
    }

    /**
     * The variables for rendering a template bound to {@code model} from the record {@code id}.
     *
     * @throws BusinessException when there is no such record
     */
    public Map<String, Object> read(String model, Serializable id, TemplateUsage usage) {
        Plan shown = planShown(model, usage);
        Plan compared = planCompared(model, usage);
        Serializable rowId = IdUtils.formatId(model, id);
        return ContextUtils.withoutPermissionChecks(() -> {
            // On this copy of the context only: what a page masks, a mail the author wrote does not.
            ContextHolder.getContext().setDataMask(false);
            Map<String, Object> row = fetch(model, rowId, shown, ConvertType.DISPLAY);
            if (row == null) {
                throw new BusinessException("{0} record {1} does not exist.", model, String.valueOf(id));
            }
            Map<String, Object> variables = new LinkedHashMap<>();
            shown.paths.forEach((path, stored) -> put(variables, path, textOf(valueAt(row, stored))));
            for (LoopPlan loop : shown.loops) {
                variables.put(loop.list, rowsOf(valueAt(row, loop.list), loop));
            }
            if (!compared.isEmpty()) {
                Map<String, Object> rawRow = fetch(model, rowId, compared, ConvertType.REFERENCE);
                Map<String, Object> raw = new LinkedHashMap<>();
                if (rawRow != null) {
                    compared.paths.forEach((path, stored) -> put(raw, path, rawOf(valueAt(rawRow, stored))));
                }
                variables.put(TemplateBlocks.RAW_ROOT, raw);
            }
            return variables;
        });
    }

    // ---- planning -------------------------------------------------------------------------------

    /** One loop table: its list, and each column's path among the fields read for an item. */
    private record LoopPlan(String list, Map<String, List<String>> columns) {
    }

    /**
     * A read: the tree of fields to fetch, and for each template path where its value lands in the
     * row that comes back.
     */
    private static final class Plan {
        final Map<String, Object> tree = new LinkedHashMap<>();
        final Map<String, List<String>> paths = new LinkedHashMap<>();
        final List<LoopPlan> loops = new ArrayList<>();

        boolean isEmpty() {
            return paths.isEmpty() && loops.isEmpty();
        }
    }

    private Plan planShown(String model, TemplateUsage usage) {
        Plan plan = new Plan();
        for (String path : usage.printed()) {
            List<String> stored = storedPath(model, path);
            if (stored != null && !endsOnList(model, stored)) {
                addPath(plan.tree, stored);
                plan.paths.put(path, stored);
            }
        }
        Set<String> lists = new LinkedHashSet<>();
        for (TemplateBlocks.Loop loop : usage.loops()) {
            MetaField list = models.field(model, loop.list());
            if (list == null || !FieldType.TO_MANY_TYPES.contains(list.getFieldType()) || !lists.add(loop.list())) {
                continue;
            }
            Map<String, List<String>> columns = new LinkedHashMap<>();
            Map<String, Object> items = subTree(plan.tree, loop.list());
            // Read as rows even when no column can be: the table then repeats empty rows, not nothing.
            items.computeIfAbsent("id", key -> new LinkedHashMap<String, Object>());
            for (String column : loop.columns()) {
                List<String> stored = storedPath(list.getRelatedModel(), column);
                if (stored != null) {
                    addPath(items, stored);
                    columns.put(column, stored);
                }
            }
            plan.loops.add(new LoopPlan(loop.list(), columns));
        }
        return plan;
    }

    private Plan planCompared(String model, TemplateUsage usage) {
        Plan plan = new Plan();
        for (TemplateBlocks.Condition condition : usage.conditions()) {
            List<String> stored = storedPath(model, condition.field());
            if (stored == null) {
                continue;
            }
            addPath(plan.tree, stored);
            if (endsOnList(model, stored)) {
                // A list is only ever asked whether it is empty: its rows' ids answer that.
                subTree(plan.tree, stored.toArray(String[]::new)).computeIfAbsent("id", key -> new LinkedHashMap<String, Object>());
            }
            plan.paths.put(condition.field(), stored);
        }
        return plan;
    }

    /**
     * The path with every copied field replaced by the path it copies, or null when some part of it
     * is not on its model or it is followed past a field that is not a relation.
     */
    private List<String> storedPath(String model, String path) {
        List<String> pending = new ArrayList<>(Arrays.asList(path.split("\\.")));
        List<String> stored = new ArrayList<>();
        String current = model;
        while (!pending.isEmpty()) {
            if (current == null || stored.size() + pending.size() > TemplateSourceRules.MAX_SEGMENTS) {
                return null;
            }
            String name = pending.removeFirst();
            MetaField field = models.field(current, name);
            if (field == null) {
                return null;
            }
            String copied = field.getCascadedField();
            if (copied != null && !copied.isBlank()) {
                pending.addAll(0, Arrays.asList(copied.split("\\.")));
                continue;
            }
            stored.add(name);
            current = pending.isEmpty() || !FieldType.RELATED_TYPES.contains(field.getFieldType())
                    ? null : field.getRelatedModel();
        }
        return stored;
    }

    private boolean endsOnList(String model, List<String> stored) {
        String current = model;
        MetaField field = null;
        for (String name : stored) {
            field = models.field(current, name);
            current = field == null ? null : field.getRelatedModel();
        }
        return field != null && FieldType.TO_MANY_TYPES.contains(field.getFieldType());
    }

    private static void addPath(Map<String, Object> tree, List<String> parts) {
        subTree(tree, parts.toArray(String[]::new));
    }

    private static Map<String, Object> subTree(Map<String, Object> tree, String... parts) {
        Map<String, Object> node = tree;
        for (String part : parts) {
            node = asTree(node.computeIfAbsent(part, key -> new LinkedHashMap<String, Object>()));
        }
        return node;
    }

    // ---- reading --------------------------------------------------------------------------------

    private Map<String, Object> fetch(String model, Serializable id, Plan plan, ConvertType convertType) {
        FlexQuery query = new FlexQuery(fieldsOf(plan.tree));
        SubQueries subQueries = new SubQueries();
        plan.tree.forEach((field, children) -> {
            if (!asTree(children).isEmpty()) {
                subQueries.expand(field, subQueryOf(asTree(children)));
            }
        });
        if (!subQueries.getQueryMap().isEmpty()) {
            query.setSubQueries(subQueries);
        }
        query.setConvertType(convertType);
        query.setFilters(new Filters().eq("id", id));
        List<Map<String, Object>> rows = modelService.searchList(model, query);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static SubQuery subQueryOf(Map<String, Object> tree) {
        SubQuery subQuery = new SubQuery(fieldsOf(tree));
        Map<String, SubQuery> nested = new LinkedHashMap<>();
        tree.forEach((field, children) -> {
            if (!asTree(children).isEmpty()) {
                nested.put(field, subQueryOf(asTree(children)));
            }
        });
        if (!nested.isEmpty()) {
            subQuery.setSubQueries(nested);
        }
        return subQuery;
    }

    private static List<String> fieldsOf(Map<String, Object> tree) {
        List<String> fields = new ArrayList<>();
        fields.add("id");
        tree.keySet().stream().filter(field -> !"id".equals(field)).forEach(fields::add);
        return fields;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asTree(Object node) {
        return (Map<String, Object>) node;
    }

    // ---- shaping --------------------------------------------------------------------------------

    private static List<Map<String, Object>> rowsOf(Object value, LoopPlan loop) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (value instanceof Collection<?> items) {
            for (Object item : items) {
                Map<String, Object> row = new LinkedHashMap<>();
                loop.columns.forEach((column, stored) -> put(row, column, textOf(valueAt(item, stored))));
                rows.add(row);
            }
        }
        return rows;
    }

    private static Object valueAt(Object row, List<String> path) {
        Object current = row;
        for (String part : path) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static Object valueAt(Object row, String field) {
        return valueAt(row, List.of(field));
    }

    /** Sets the value at a dotted path, as nested maps; a name already holding a plain value gives way. */
    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> node = target;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = node.get(parts[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                node.put(parts[i], next);
            }
            node = (Map<String, Object>) next;
        }
        // A record already read for its fields is not overwritten by its own display name.
        if (!(node.get(parts[parts.length - 1]) instanceof Map<?, ?>)) {
            node.put(parts[parts.length - 1], value);
        }
    }

    /**
     * What a template prints for a value. A relation shows its display name, never the map — when it
     * was also followed it arrives as the record read for that, so its {@code name} stands in. A
     * value that is missing prints nothing, in a subject too.
     */
    private static Object textOf(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> map) {
            Object name = map.containsKey("displayName") ? map.get("displayName") : map.get("name");
            return name == null ? "" : name;
        }
        if (value instanceof ModelReference reference) {
            return reference.getDisplayName() == null ? "" : reference.getDisplayName();
        }
        if (value instanceof OptionReference option) {
            return option.getLabel() == null ? "" : option.getLabel();
        }
        if (value instanceof Collection<?> items) {
            List<String> texts = new ArrayList<>();
            for (Object item : items) {
                texts.add(String.valueOf(textOf(item)));
            }
            return String.join(", ", texts);
        }
        return value;
    }

    /** What a condition compares: an option by its code, a relation by its id, anything else as it is. */
    private static Object rawOf(Object value) {
        if (value instanceof OptionReference option) {
            return option.getItemCode();
        }
        if (value instanceof ModelReference reference) {
            return reference.getId();
        }
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("itemCode")) {
                return map.get("itemCode");
            }
            return map.containsKey("id") ? map.get("id") : map;
        }
        if (value instanceof Collection<?> items) {
            List<Object> raw = new ArrayList<>();
            for (Object item : items) {
                raw.add(rawOf(item));
            }
            return raw;
        }
        return value;
    }
}
