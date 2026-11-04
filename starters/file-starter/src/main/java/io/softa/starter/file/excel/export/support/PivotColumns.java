package io.softa.starter.file.excel.export.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.file.vo.PivotSpec;

/**
 * Appends pivot columns to an exported table — see {@link PivotSpec}.
 *
 * <p>Three reads, then arithmetic. The key model once: its rows ARE the column set, the whole
 * domain the caller may see (a multi-country domain is already narrowed to the caller's countries
 * by the ORM), ordered by group then label — never the keys the exported rows happen to hold, so the
 * sheet has the same columns whatever the filter. The source rows for the exported ids, batched.
 * And, when the spec names a row-group field, the exported rows' raw group ids and the groups of
 * what they point at, because the export itself reads with DISPLAY conversion and a display name
 * cannot be joined on.
 *
 * <p>The cell rule is the one the list view applies: a value wins; without one, a column whose
 * group differs from the row's does not apply and shows {@link PivotSpec#getEmptyText()} — a New
 * Zealand department under "Full Time (SG)" — and a column that does apply shows 0, because "none of
 * this type here" is a number, not a gap.
 */
@Component
public class PivotColumns {

    public static final String DEFAULT_EMPTY_TEXT = "—";

    @Autowired
    private ModelService<?> modelService;

    /** One pivot column: a key, its label, and the group it belongs to (null when ungrouped). */
    public record Column(String keyId, String label, String group) {
    }

    /**
     * Append the pivot's headers and cells to a table already extracted for {@code modelName}.
     *
     * @param modelName the exported model
     * @param spec      the pivot
     * @param rows      the exported rows as fetched (DISPLAY-converted); only their ids are read
     * @param headers   the header list to append to (mutated)
     * @param table     the rows table to append to (mutated, same order as {@code rows})
     */
    public void append(String modelName, PivotSpec spec, List<Map<String, Object>> rows,
                       List<String> headers, List<List<Object>> table) {
        validate(modelName, spec);
        List<Column> columns = columns(spec);
        if (columns.isEmpty()) {
            return;
        }
        List<Object> primaryIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object id = row.get(ModelConstant.ID);
            if (id != null) {
                primaryIds.add(id);
            }
        }
        Map<String, Map<String, Object>> values = values(spec, primaryIds);
        Map<String, String> rowGroups = StringUtils.isBlank(spec.getRowGroupField())
                ? Map.of()
                : rowGroups(modelName, spec, primaryIds);
        String emptyText = StringUtils.isBlank(spec.getEmptyText()) ? DEFAULT_EMPTY_TEXT : spec.getEmptyText();
        columns.forEach(column -> headers.add(column.label()));
        for (int i = 0; i < rows.size(); i++) {
            String primaryId = String.valueOf(rows.get(i).get(ModelConstant.ID));
            Map<String, Object> byKey = values.getOrDefault(primaryId, Map.of());
            String rowGroup = rowGroups.get(primaryId);
            for (Column column : columns) {
                table.get(i).add(cell(byKey.get(column.keyId()), rowGroup, column.group(), emptyText));
            }
        }
    }

    /** Every field the spec names must exist, or the sheet would silently come out without the pivot. */
    static void validate(String modelName, PivotSpec spec) {
        Assert.isTrue(StringUtils.isNoneBlank(spec.getSource(), spec.getJoinField(), spec.getKeyField(),
                        spec.getKeyModel(), spec.getValueField()),
                "Pivot: source, joinField, keyField, keyModel and valueField are all required.");
        requireField(spec.getSource(), spec.getJoinField());
        requireField(spec.getSource(), spec.getKeyField());
        requireField(spec.getSource(), spec.getValueField());
        Assert.isTrue(ModelManager.existModel(spec.getKeyModel()), "Pivot: key model `{0}` does not exist.", spec.getKeyModel());
        if (StringUtils.isNotBlank(spec.getKeyModelGroupField())) {
            requireField(spec.getKeyModel(), spec.getKeyModelGroupField());
        }
        if (StringUtils.isNotBlank(spec.getRowGroupField())) {
            requireField(modelName, spec.getRowGroupField());
            Assert.isTrue(StringUtils.isNotBlank(spec.getKeyModelGroupField()),
                    "Pivot: rowGroupField needs keyModelGroupField to compare against.");
        }
    }

    private static void requireField(String modelName, String fieldName) {
        if (!ModelManager.existModel(modelName) || !ModelManager.existField(modelName, fieldName)) {
            throw new IllegalArgumentException("Pivot: field `{0}` does not exist in model `{1}`.", fieldName, modelName);
        }
    }

    /** The column set, read from the key model through the same read the list view uses. */
    List<Column> columns(PivotSpec spec) {
        String groupField = spec.getKeyModelGroupField();
        FlexQuery query = new FlexQuery(StringUtils.isBlank(groupField) ? List.of() : List.of(groupField), new Filters());
        List<Map<String, Object>> keyRows = modelService.searchName(spec.getKeyModel(), query,
                Page.ofCursorPage(BaseConstant.MAX_BATCH_SIZE));
        return buildColumns(keyRows, groupField, Boolean.TRUE.equals(spec.getSuffixGroup()));
    }

    /** Pure: order by group then label; suffix the group when asked. */
    static List<Column> buildColumns(List<Map<String, Object>> keyRows, String groupField, boolean suffixGroup) {
        List<Column> columns = new ArrayList<>();
        for (Map<String, Object> row : keyRows) {
            String keyId = referenceId(row.get(ModelConstant.ID));
            if (keyId == null) {
                continue;
            }
            String baseLabel = row.get(ModelConstant.DISPLAY_NAME) == null
                    ? keyId : String.valueOf(row.get(ModelConstant.DISPLAY_NAME));
            String group = StringUtils.isBlank(groupField) ? null : referenceId(row.get(groupField));
            String label = group != null && suffixGroup ? baseLabel + " (" + group + ")" : baseLabel;
            columns.add(new Column(keyId, label, group));
        }
        columns.sort(Comparator.comparing((Column c) -> c.group() == null ? "" : c.group())
                .thenComparing(Column::label));
        return columns;
    }

    /** {@code primaryId → keyId → value}, from the source long rows for these primary ids. */
    Map<String, Map<String, Object>> values(PivotSpec spec, List<Object> primaryIds) {
        Map<String, Map<String, Object>> index = new HashMap<>();
        for (List<Object> chunk : chunks(primaryIds)) {
            FlexQuery query = new FlexQuery(List.of(spec.getJoinField(), spec.getKeyField(), spec.getValueField()),
                    new Filters().in(spec.getJoinField(), chunk));
            query.setLimitSize(BaseConstant.MAX_BATCH_SIZE);
            for (Map<String, Object> row : modelService.searchList(spec.getSource(), query)) {
                String primaryId = referenceId(row.get(spec.getJoinField()));
                String keyId = referenceId(row.get(spec.getKeyField()));
                if (primaryId == null || keyId == null) {
                    continue;
                }
                index.computeIfAbsent(primaryId, ignored -> new LinkedHashMap<>()).put(keyId, row.get(spec.getValueField()));
            }
        }
        return index;
    }

    /**
     * {@code primaryId → group}: the exported rows' raw group ids (a second, TYPE_CAST read — the export
     * rows were DISPLAY-converted), then the group field of what they point at.
     */
    Map<String, String> rowGroups(String modelName, PivotSpec spec, List<Object> primaryIds) {
        MetaField groupRef = ModelManager.getModelField(modelName, spec.getRowGroupField());
        String groupModel = groupRef.getRelatedModel();
        Assert.isTrue(StringUtils.isNotBlank(groupModel) && ModelManager.existField(groupModel, spec.getKeyModelGroupField()),
                "Pivot: rowGroupField `{0}` must point at a model carrying `{1}`.", spec.getRowGroupField(), spec.getKeyModelGroupField());
        Map<String, String> groupIdByPrimary = new HashMap<>();
        Set<Object> groupIds = new LinkedHashSet<>();
        for (List<Object> chunk : chunks(primaryIds)) {
            FlexQuery query = new FlexQuery(List.of(ModelConstant.ID, spec.getRowGroupField()),
                    new Filters().in(ModelConstant.ID, chunk));
            query.setLimitSize(BaseConstant.MAX_BATCH_SIZE);
            for (Map<String, Object> row : modelService.searchList(modelName, query)) {
                String groupId = referenceId(row.get(spec.getRowGroupField()));
                if (groupId != null) {
                    groupIdByPrimary.put(String.valueOf(row.get(ModelConstant.ID)), groupId);
                    groupIds.add(groupId);
                }
            }
        }
        Map<String, String> groupById = new HashMap<>();
        for (List<Object> chunk : chunks(new ArrayList<>(groupIds))) {
            FlexQuery query = new FlexQuery(List.of(ModelConstant.ID, spec.getKeyModelGroupField()),
                    new Filters().in(ModelConstant.ID, chunk));
            query.setLimitSize(BaseConstant.MAX_BATCH_SIZE);
            for (Map<String, Object> row : modelService.searchList(groupModel, query)) {
                String group = referenceId(row.get(spec.getKeyModelGroupField()));
                if (group != null) {
                    groupById.put(String.valueOf(row.get(ModelConstant.ID)), group);
                }
            }
        }
        Map<String, String> result = new HashMap<>();
        groupIdByPrimary.forEach((primaryId, groupId) -> {
            String group = groupById.get(groupId);
            if (group != null) {
                result.put(primaryId, group);
            }
        });
        return result;
    }

    /** Pure: the cell rule shared with the list view. */
    static Object cell(Object value, String rowGroup, String columnGroup, String emptyText) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            return value;
        }
        if (rowGroup != null && columnGroup != null && !rowGroup.equals(columnGroup)) {
            return emptyText;
        }
        return 0;
    }

    /** A reference comes back as `{id, displayName}` or as the bare id; either way, the id. */
    static String referenceId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Object id = map.get(ModelConstant.ID);
            return id == null ? null : String.valueOf(id);
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static List<List<Object>> chunks(Collection<Object> ids) {
        List<List<Object>> chunks = new ArrayList<>();
        List<Object> current = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Object id : ids) {
            if (!seen.add(id)) {
                continue;
            }
            current.add(id);
            if (current.size() >= BaseConstant.DEFAULT_BATCH_SIZE) {
                chunks.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }
}
