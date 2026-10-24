package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.utils.Cast;
import io.softa.framework.orm.aspect.CallerPermissions;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.utils.ReflectTool;
import io.softa.framework.orm.vo.ModelReference;

/**
 * OneToOne/ManyToOne field group processor.
 * Combine the OneToOne/ManyToOne field itself, cascaded fields depend on it,
 * and the custom cascaded fields that reference it.
 */
@Slf4j
public class XToOneGroupProcessor extends BaseProcessor {

    /**
     * Cascaded fields depending on the same OneToOne/ManyToOne field, added by XToOneGroupProcessorFactory.
     * READ: calculate and return the dynamic cascaded fields.
     * CREATE/UPDATE: calculate the stored cascaded fields.
     */
    private final List<MetaField> cascadedFields = new ArrayList<>(0);
    // The fields of the related model that need to be expanded through the OneToOne/ManyToOne field.
    private final Set<String> expandFields = Sets.newHashSet(ModelConstant.ID);
    // The part of expandFields the model itself decides — id, displayName and declared cascaded fields — as
    // opposed to what a subQuery or a dotted field asks for. The only part read for a row outside the
    // caller's row range.
    private final Set<String> labelFields = Sets.newHashSet(ModelConstant.ID);
    // A dotted field of the request (`managerId.dateOfBirth`) asks past the label: the related rows are then
    // read within the caller's range, as for a subQuery.
    private boolean requestedPastLabel;
    private final FlexQuery flexQuery;
    private final SubQuery subQuery;

    /**
     * Constructor of the ManyToOne/OneToOne group processor.
     *
     * @param metaField xToOne field metadata object
     * @param accessType Access type
     * @param flexQuery flexQuery object
     */
    public XToOneGroupProcessor(MetaField metaField, AccessType accessType, FlexQuery flexQuery) {
        super(metaField, accessType);
        this.flexQuery = flexQuery;
        // The FK stores the related row's id (already in expandFields) — the join key.
        // For ManyToOne/OneToOne fields, also read the displayName fields of the related model.
        this.labelFields.addAll(ModelManager.getModelDisplayName(metaField.getRelatedModel()));
        this.expandFields.addAll(this.labelFields);
        // When the flexQuery has subQuery base on the ManyToOne/OneToOne field,
        // expand the fields of the related model to be read.
        this.subQuery = flexQuery == null ? null : flexQuery.extractSubQuery(metaField.getFieldName());
        if (subQuery != null && !CollectionUtils.isEmpty(subQuery.getFields())) {
            this.expandFields.addAll(subQuery.getFields());
        }
    }

    /**
     * Add the cascaded field of the related model to the expandFields.
     * For example, add the `name` field in `deptId.name` to the expandFields.
     *
     * @param cascadedField Cascaded field metadata object
     */
    public void addCascadedField(MetaField cascadedField) {
        this.cascadedFields.add(cascadedField);
        String relatedField = cascadedField.getDependentFields().get(1);
        this.expandFields.add(relatedField);
        // A cascaded field the model declares is part of what its references show. A dotted field built
        // from the request is the caller's own question about the related row, and past its label it is
        // answered only for the rows the caller may read.
        if (!cascadedField.getFieldName().contains(".") || this.labelFields.contains(relatedField)) {
            this.labelFields.add(relatedField);
        } else {
            this.requestedPastLabel = true;
        }
    }

    /**
     * Batch process the input data of the cascaded fields and ManyToOne/OneToOne fields.
     *
     * @param rows Data collection
     */
    public void batchProcessInputRows(List<Map<String, Object>> rows) {
        processNestedOneToOneRows(rows);
        if (!cascadedFields.isEmpty()) {
            Map<Serializable, Map<String, Object>> relatedRowMap = getRelatedModelRowMap(rows);
            // Calculate the stored cascaded field first.
            cascadedFields.forEach(field -> batchProcessOutputRowsCascaded(field, rows, relatedRowMap));
        }
        XToOneProcessor xToOneField = new XToOneProcessor(metaField, accessType);
        xToOneField.batchProcessInputRows(rows);
    }

    /**
     * Batch process the output data of the cascaded field and the ManyToOne/OneToOne field.
     *
     * @param rows The list of output data
     */
    public void batchProcessOutputRows(List<Map<String, Object>> rows) {
        if (flexQuery.isAcrossTimeline()
                && ModelManager.isTimelineModel(metaField.getModelName())
                && ModelManager.isTimelineModel(metaField.getRelatedModel())
                && ConvertType.EXPAND_TYPES.contains(flexQuery.getConvertType())) {
            // When the main model and related model are both timeline models in a cross-timeline query,
            // the fields of the related model `displayNames` has been queried, compute the displayName value directly.
            processRelatedTimeLineModelDisplayName(rows);
        } else {
            Map<Serializable, Map<String, Object>> relatedRowMap = getRelatedModelRowMap(rows);
            // Before expand the ManyToOne/OneToOne field, calculate the dynamic cascaded field first.
            cascadedFields.forEach(sf -> batchProcessOutputRowsCascaded(sf, rows, relatedRowMap));
            XToOneProcessor xToOneField = new XToOneProcessor(metaField, accessType, flexQuery);
            // Expand the ManyToOne/OneToOne field with displayName, or related model row according to subQuery.
            xToOneField.batchProcessOutputRows(rows, relatedRowMap);
        }
    }

    /**
     * Get the mapping of related model rows.
     *
     * <p>Without a subQuery only the label fields are read, across the caller's row range: a referenced
     * row's label is shown wherever the reference is. With one — or with a dotted field of the request
     * that reads past the label — the requested fields and nested subQueries are read through
     * {@link ReflectTool#searchRelatedRows}, and a referenced row it does not return — outside the
     * caller's range — falls back to its label alone, the dotted field left empty.
     *
     * @param rows Data list
     * @return {relatedModelId: {related model row}}
     */
    public Map<Serializable, Map<String, Object>> getRelatedModelRowMap(List<Map<String, Object>> rows) {
        Map<Serializable, Map<String, Object>> relatedValueMap = new HashMap<>();
        // The FK stores the related row's id; query and index the related rows by id.
        Set<Serializable> relatedIds = rows.stream().map(r -> (Serializable) r.get(metaField.getFieldName()))
                .filter(IdUtils::validId).collect(Collectors.toSet());
        if (CollectionUtils.isEmpty(relatedIds)) {
            return relatedValueMap;
        }
        Set<Serializable> labelOnlyIds = relatedIds;
        if (subQuery != null || requestedPastLabel) {
            FlexQuery relatedFlexQuery = relatedFlexQuery(this.expandFields, relatedIds);
            // Propagate nested subQueries so the related model can resolve its own relational subQueries
            if (subQuery != null && !CollectionUtils.isEmpty(subQuery.getSubQueries())) {
                SubQueries nestedSubQueries = new SubQueries();
                nestedSubQueries.setQueryMap(subQuery.getSubQueries());
                relatedFlexQuery.setSubQueries(nestedSubQueries);
            }
            List<Map<String, Object>> relatedRows = ReflectTool.searchRelatedRows(metaField.getRelatedModel(), relatedFlexQuery);
            relatedRows.forEach(row -> relatedValueMap.put((Serializable) row.get(ModelConstant.ID), row));
            labelOnlyIds = relatedIds.stream()
                    .filter(id -> !relatedValueMap.containsKey(IdUtils.formatId(metaField.getRelatedModel(), id)))
                    .collect(Collectors.toSet());
        }
        if (!labelOnlyIds.isEmpty()) {
            FlexQuery labelFlexQuery = relatedFlexQuery(this.labelFields, labelOnlyIds);
            List<Map<String, Object>> labelRows = ReflectTool.searchListIgnoringRowScope(metaField.getRelatedModel(), labelFlexQuery);
            labelRows.forEach(row -> relatedValueMap.put((Serializable) row.get(ModelConstant.ID), row));
        }
        return relatedValueMap;
    }

    /**
     * Query the related model rows by id: [id, "IN", relatedIds], inactive and soft-deleted rows included.
     */
    private FlexQuery relatedFlexQuery(Set<String> fields, Set<Serializable> relatedIds) {
        Filters filters = Filters.of(ModelConstant.ID, Operator.IN, relatedIds);
        MetaModel relatedMetaModel = ModelManager.getModel(metaField.getRelatedModel());
        if (relatedMetaModel.isActiveControl()) {
            filters.in(ModelConstant.ACTIVE_CONTROL_FIELD, List.of(true, false));
        }
        if (relatedMetaModel.isSoftDelete()) {
            filters.in(ModelConstant.SOFT_DELETED_FIELD, List.of(true, false));
        }
        FlexQuery relatedFlexQuery = new FlexQuery(new HashSet<>(fields), filters);
        relatedFlexQuery.setFilterControl(FilterControl.bypassAll());
        if (flexQuery != null) {
            relatedFlexQuery.setConvertType(flexQuery.getConvertType());
        }
        return relatedFlexQuery;
    }

    /**
     * Batch process the Value of the cascadedFields.
     * READ: the stored cascaded fields.
     * CREATE/UPDATE: the dynamic cascaded fields.
     *
     * @param cascadedField Cascaded field object
     * @param rows Data list
     * @param relatedValueMap related model data: {relatedModelId: {related model row}}
     */
    private void batchProcessOutputRowsCascaded(MetaField cascadedField, List<Map<String, Object>> rows, Map<Serializable, Map<String, Object>> relatedValueMap) {
        List<String> casFields = cascadedField.getDependentFields();
        String xToOneFieldName = casFields.getFirst();
        MetaField xToOneMeta = ModelManager.getModelField(modelName, xToOneFieldName);
        String cascadeModel = xToOneMeta.getRelatedModel();
        rows.forEach(row -> {
            Serializable key = (Serializable) row.get(xToOneFieldName);
            Object value;
            if (IdUtils.validId(key)) {
                key = IdUtils.formatId(cascadeModel, key);
                if (relatedValueMap.containsKey(key)) {
                    value = relatedValueMap.get(key).get(casFields.get(1));
                } else {
                    log.warn("Model {}, the {} field value does not exist in the related model {}: {}",
                            metaField.getModelName(), casFields.getFirst(), metaField.getRelatedModel(), row);
                    value = null;
                }
            } else {
                value = null;
            }
            row.put(cascadedField.getFieldName(), value);
        });
    }

    /**
     * When the main model and related model are both timeline models in a `acrossTimeline` query,
     * the `displayNames` fields of the related timeline model has been queried,
     * and the displayName is directly computed and assigned to the ManyToOne/OneToOne field.
     *
     * @param rows Data list
     */
    public void processRelatedTimeLineModelDisplayName(List<Map<String, Object>> rows) {
        List<String> displayFields = ModelManager.getModelDisplayName(metaField.getRelatedModel());
        String xToOneFieldName = metaField.getFieldName();
        rows.forEach(row -> {
            Map<String, Object> relatedRow = new HashMap<>();
            List<String> cascadedTimeLineFields = new ArrayList<>();
            row.forEach((key, value) -> {
                if (key.contains(".") && key.startsWith(xToOneFieldName)) {
                    // Only process the timeline model `displayName` fields with `.` separator flag, such as deptId.name
                    String[] fields = StringUtils.split(key, ".");
                    relatedRow.put(fields[1], value);
                    cascadedTimeLineFields.add(key);
                }
            });
            if (!relatedRow.isEmpty()) {
                // Compute the displayName of related timeline model, and assign it to the ManyToOne/OneToOne field.
                List<Object> displayValues = displayFields.stream().map(relatedRow::get).filter(n -> n != null && n != "").collect(Collectors.toList());
                String displayName = StringUtils.join(displayValues, StringConstant.DISPLAY_NAME_SEPARATOR);
                Serializable relatedId = (Serializable) row.get(xToOneFieldName);
                Object value = ConvertType.REFERENCE.equals(flexQuery.getConvertType()) ?
                        ModelReference.of(relatedId, displayName) : displayName;
                row.put(xToOneFieldName, value);
            }
            // After expanding the ManyToOne/OneToOne field, remove the `.` separator flag field specified in the `displayName` fields
            cascadedTimeLineFields.forEach(row::remove);
        });
    }

    /**
     * The related ids the updated rows point at now, by main row id. Read past the row range: the main
     * rows have passed their own update check, and only the link is compared.
     */
    private Map<Serializable, Object> storedLinks(List<Map<String, Object>> rows) {
        List<Serializable> ids = rows.stream()
                .filter(row -> row.get(fieldName) instanceof Map<?, ?>)
                .map(row -> (Serializable) row.get(ModelConstant.ID))
                .filter(IdUtils::validId)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        FlexQuery query = new FlexQuery(List.of(ModelConstant.ID, fieldName), Filters.of(ModelConstant.ID, Operator.IN, ids));
        // The stored key itself, not a reference built from it.
        query.setConvertType(ConvertType.TYPE_CAST);
        Map<Serializable, Object> links = new HashMap<>();
        ReflectTool.searchListIgnoringRowScope(modelName, query)
                .forEach(stored -> links.put(IdUtils.formatId(modelName, (Serializable) stored.get(ModelConstant.ID)),
                        stored.get(fieldName)));
        return links;
    }

    /**
     * A nested object that names a related row and changes it must name the row this record already
     * points at. Naming another — a colleague's bank account through one's own record — would write that
     * row as part of this one, and repoint this record at it, under whatever the caller may do here.
     */
    private void assertOwnLink(Map<String, Object> row, Serializable relatedId, Map<Serializable, Object> storedLinks) {
        Serializable mainId = (Serializable) row.get(ModelConstant.ID);
        Object stored = mainId == null ? null : storedLinks.get(IdUtils.formatId(modelName, mainId));
        if (stored == null || !String.valueOf(stored).equals(String.valueOf(relatedId))) {
            throw new PermissionException("Model field {0}:{1} can change only the {2} record it already points at.",
                    modelName, fieldName, metaField.getRelatedModel());
        }
    }

    /**
     * Process nested OneToOne value objects before the field is formatted to its join key.
     *
     * <p>The main row's FK stores the related row's id. A nested object with a valid id is
     * an existing related row (updated in place); without an id it is created inline and the new id is
     * linked back.
     */
    private void processNestedOneToOneRows(List<Map<String, Object>> rows) {
        if (!FieldType.ONE_TO_ONE.equals(metaField.getFieldType())) {
            return;
        }
        String relatedModel = metaField.getRelatedModel();
        List<Map<String, Object>> createRows = new ArrayList<>();
        List<Map<String, Object>> updateRows = new ArrayList<>();
        List<Map<String, Object>> createTargetRows = new ArrayList<>();
        Map<Serializable, Object> storedLinks = AccessType.UPDATE.equals(accessType) ? storedLinks(rows) : Map.of();
        for (Map<String, Object> row : rows) {
            Object value = row.get(fieldName);
            if (!(value instanceof Map<?, ?> mapValue)) {
                continue;
            }
            Map<String, Object> nestedRow = Cast.of(mapValue);
            Object relatedId = nestedRow.remove(ModelConstant.ID);
            if (IdUtils.validId(relatedId)) {
                Serializable formattedId = IdUtils.formatId(relatedModel, (Serializable) relatedId);
                if (AccessType.UPDATE.equals(accessType) && !nestedRow.isEmpty()) {
                    assertOwnLink(row, formattedId, storedLinks);
                }
                // Link the main row to the existing related row by its id.
                row.put(fieldName, formattedId);
                if (!nestedRow.isEmpty()) {
                    nestedRow.put(ModelConstant.ID, formattedId);
                    updateRows.add(nestedRow);
                }
            } else {
                createRows.add(nestedRow);
                createTargetRows.add(row);
            }
        }
        // The nested rows are the caller's own writes to the related model — an existing one named by
        // id included — so they are judged as the caller: its row range and field guards.
        List<Serializable> createdIds = CallerPermissions.asCaller(() -> {
            if (!createRows.isEmpty()) ReflectTool.checkNestedWrite(relatedModel, AccessType.CREATE, modelName, fieldName);
            return ReflectTool.createList(relatedModel, createRows);
        });
        for (int i = 0; i < createdIds.size(); i++) {
            createTargetRows.get(i).put(fieldName, createdIds.get(i));
        }
        CallerPermissions.asCaller(() -> {
            if (!updateRows.isEmpty()) ReflectTool.checkNestedWrite(relatedModel, AccessType.UPDATE, modelName, fieldName);
            return ReflectTool.updateList(relatedModel, updateRows);
        });
    }

}
