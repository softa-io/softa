package io.softa.framework.orm.jdbc.pipeline.processor;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import com.google.common.collect.Sets;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.enums.AccessType;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.domain.SubQuery;
import io.softa.framework.orm.entity.AbstractModel;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.framework.orm.utils.ReflectTool;
import io.softa.framework.orm.vo.ModelReference;

/**
 * ManyToMany field value processing.
 * Structure of ManyToMany field:
 *      Main model --(ManyToMany)--> Related model
 *      equals to:
 *      Main model <--(joinLeft field)-- Join model --(joinRight field)--> Related model
 * The join model is the mapping table of the ManyToMany field, configured in the `relatedModel` of ManyToMany
 * field metadata. The join model contains the following key fields:
 *      id: Join model id
 *      field1: Main model id (joinLeft config in ManyToMany field metadata)
 *      field2: Related model id (joinRight config in ManyToMany field metadata)
 * <p>
 * The input parameters of ManyToMany field is the ids of related model, that is: [id1, id2, id3],
 * which implies new mapping and deleted mapping maintained in the join model. The new mapping and deleted mapping
 * are calculated by querying the join model once, and comparing with the input ids.
 */
@Slf4j
public class ManyToManyProcessor extends BaseProcessor {

    private final FlexQuery flexQuery;

    private SubQuery subQuery;

    @Getter
    private boolean changed = false;

    public ManyToManyProcessor(MetaField metaField, AccessType accessType, FlexQuery flexQuery) {
        super(metaField, accessType);
        this.flexQuery = flexQuery;
        if (flexQuery != null) {
            this.subQuery = flexQuery.extractSubQuery(metaField.getFieldName());
        }
    }

    /**
     * Batch processing of ManyToMany input data.
     *
     * @param rows Input data list
     */
    @Override
    public void batchProcessInputRows(List<Map<String, Object>> rows) {
        if (AccessType.CREATE.equals(accessType)) {
            batchCreateMappingRows(rows);
        } else if (AccessType.UPDATE.equals(accessType)) {
            batchUpdateMappingRows(rows);
        }
    }

    /**
     * Extract the ids of the related model objects.
     *
     * @param valueList List of related model objects
     * @return List of ids
     */
    private List<?> extractObjectsIds(List<?> valueList) {
        return valueList.stream()
                .map(v -> BeanTool.getFieldValue((AbstractModel) v, ModelConstant.ID))
                .toList();
    }

    /**
     * Batch CREATE mapping relationships, which is to create new rows in the join model directly.
     *
     * @param rows Data list
     */
    private void batchCreateMappingRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> mappingRows = new ArrayList<>();
        rows.forEach(row -> {
            Serializable id = (Serializable) row.get(ModelConstant.ID);
            Object value = row.get(fieldName);
            if (value instanceof List<?> valueList && !valueList.isEmpty()) {
                if (valueList.getFirst() instanceof AbstractModel) {
                    valueList = extractObjectsIds(valueList);
                }
                List<Serializable> rightIds = IdUtils.formatIds(metaField.getJoinModel(), metaField.getJoinRight(), valueList);
                rightIds.forEach(i -> mappingRows.add(
                        new HashMap<>(Map.of(metaField.getJoinLeft(), id, metaField.getJoinRight(), i))
                ));
            }
        });
        if (!CollectionUtils.isEmpty(mappingRows)) {
            changed = true;
            ReflectTool.createList(metaField.getJoinModel(), mappingRows);
        }
    }

    /**
     * Batch UPDATE mapping relationships.
     * The new mapping and deleted mapping are calculated by querying the join model once,
     * and comparing with the input ids of the ManyToMany field.
     *
     * @param rows Data list
     */
    private void batchUpdateMappingRows(List<Map<String, Object>> rows) {
        // Map<mainModelId, Map<relatedModelId, joinModelId>>: get the existing mapping relationships.
        Map<Serializable, Map<Serializable, Serializable>> mToMIdsMapping = getPreviousManyToManyRows(rows);
        // Extract the new mapping rows: newMToMRows, and the join model ids to be deleted: deleteJoinIds
        List<Map<String, Object>> newMToMRows = new ArrayList<>();
        List<Serializable> deleteJoinIds = new ArrayList<>();
        rows.forEach(row -> {
            Serializable id = (Serializable) row.get(ModelConstant.ID);
            Object value = row.get(fieldName);
            if (value == null && row.containsKey(fieldName) && mToMIdsMapping.containsKey(id)) {
                deleteJoinIds.addAll(mToMIdsMapping.get(id).values());
            } else if (value instanceof List<?> valueList) {
                if (valueList.isEmpty() && mToMIdsMapping.containsKey(id)) {
                    // When the ManyToMany field value is an empty list, it means to clear the mapping table data
                    deleteJoinIds.addAll(mToMIdsMapping.get(id).values());
                } else {
                    if (valueList.getFirst() instanceof AbstractModel) {
                        valueList = extractObjectsIds(valueList);
                    }
                    List<Serializable> rightIds = IdUtils.formatIds(metaField.getJoinModel(), metaField.getJoinRight(), valueList);
                    if (mToMIdsMapping.containsKey(id)) {
                        // Remove the existing ids of the relatedModel to obtain the newRightIds to be joined.
                        List<Serializable> newRightIds = new ArrayList<>(rightIds);
                        newRightIds.removeAll(mToMIdsMapping.get(id).keySet());
                        // The difference set means the relationship to be deleted.
                        List<Serializable> unlinkRightIds = new ArrayList<>(mToMIdsMapping.get(id).keySet());
                        unlinkRightIds.removeAll(rightIds);
                        if (!unlinkRightIds.isEmpty()) {
                            unlinkRightIds.forEach(i -> deleteJoinIds.add(mToMIdsMapping.get(id).get(i)));
                        }
                        rightIds = newRightIds;
                    }
                    rightIds.forEach(i -> newMToMRows.add(
                            new HashMap<>(Map.of(metaField.getJoinLeft(), id, metaField.getJoinRight(), i))
                    ));
                }
            }
        });
        if (!CollectionUtils.isEmpty(newMToMRows) || !CollectionUtils.isEmpty(deleteJoinIds)) {
            changed = true;
            // Create join model rows
            ReflectTool.createList(metaField.getJoinModel(), newMToMRows);
            // Delete join model rows
            ReflectTool.deleteList(metaField.getJoinModel(), deleteJoinIds);
        }
    }

    /**
     * Query original data from the join model and grouped by the main model id.
     *
     * @param rows Input data list
     * @return mToMIdsMapping, `Map<mainModelId, Map<relatedModelId, joinModelId>>`,
     *      which is used to find the id of the join model through joinLeft (mainModelId)
     *      and joinRight (relatedModelId) to delete the join model rows.
     */
    private Map<Serializable, Map<Serializable, Serializable>> getPreviousManyToManyRows(Collection<Map<String, Object>> rows) {
        Map<Serializable, Map<Serializable, Serializable>> mToMIdsMapping = new HashMap<>();
        List<Serializable> ids = rows.stream().map(r -> (Serializable) r.get(ModelConstant.ID)).collect(Collectors.toList());
        Set<String> fields = Sets.newHashSet(ModelConstant.ID, metaField.getJoinLeft(), metaField.getJoinRight());
        FlexQuery previousFlexQuery = new FlexQuery(fields).where(new Filters().in(metaField.getJoinLeft(), ids));
        List<Map<String, Object>> previousMToMRows = ReflectTool.searchList(metaField.getJoinModel(), previousFlexQuery);
        previousMToMRows.forEach(row -> {
            Serializable id = (Serializable) row.get(ModelConstant.ID);
            Serializable leftId = (Serializable) row.get(metaField.getJoinLeft());
            Serializable rightId = (Serializable) row.get(metaField.getJoinRight());
            if (mToMIdsMapping.containsKey(leftId)) {
                mToMIdsMapping.get(leftId).put(rightId, id);
            } else {
                mToMIdsMapping.put(leftId, new HashMap<>(Map.of(rightId, id)));
            }
        });
        return mToMIdsMapping;
    }

    /**
     * Batch READ ManyToMany fields
     *
     * @param rows Data list
     */
    @Override
    public void batchProcessOutputRows(List<Map<String, Object>> rows) {
        List<Serializable> mainModelIds = rows.stream()
                .map(row -> (Serializable) row.get(ModelConstant.ID))
                .toList();
        if (subQuery != null && Boolean.TRUE.equals(subQuery.getCount())) {
            expandRowsWithRightCount(mainModelIds, rows);
            return;
        }
        List<Map<String, Object>> joinRows;
        if (subQuery == null && ConvertType.EXPAND_TYPES.contains(flexQuery.getConvertType())) {
            // Set the ManyToMany field value to displayNames when not expanded by default
            joinRows = getJoinRowsWithRightDisplayName(mainModelIds);
        } else {
            // Expand the join model rows, according to the `subQuery` object.
            joinRows = getJoinRowsWithRightModelData(mainModelIds);
        }
        // Group by `joinLeft` of the join model, which stores the main model id.
        Map<Serializable, List<Object>> groupedValues = groupJoinRows(joinRows);
        rows.forEach(row -> {
            List<Object> relatedRows = groupedValues.get((Serializable) row.get(ModelConstant.ID));
            if (relatedRows == null) {
                relatedRows = Collections.emptyList();
            }
            // Update the ManyToMany field value with the related model data
            row.put(fieldName, relatedRows);
        });
    }

    /**
     * Set the ManyToMany field value to the count of related records.
     *
     * @param mainModelIds Main model ids
     * @param rows         Main model data list
     */
    private void expandRowsWithRightCount(List<Serializable> mainModelIds, List<Map<String, Object>> rows) {
        Filters filters = new Filters().in(metaField.getJoinLeft(), mainModelIds);
        // When there is a subQuery filters, merge them with `AND` logic
        filters.and(subQuery.getFilters());
        // count subQuery on the join model
        FlexQuery joinModelFlexQuery = new FlexQuery(List.of(metaField.getJoinLeft()), filters);
        // Count is automatically added during the groupBy operation
        joinModelFlexQuery.setGroupBy(metaField.getJoinLeft());
        List<Map<String, Object>> countRows = ReflectTool.searchList(metaField.getJoinModel(), joinModelFlexQuery);
        Map<Serializable, Integer> countMap = countRows.stream()
                .collect(Collectors.toMap(
                        row -> (Serializable) row.get(metaField.getJoinLeft()),
                        row -> (Integer) row.get(ModelConstant.COUNT)));
        rows.forEach(row -> row.put(fieldName, countMap.get((Serializable) row.get(ModelConstant.ID))));
    }

    /**
     * Expand the join model rows with the right model displayName.
     *
     * @param mainModelIds Main model ids
     * @return Join model rows: [{id, joinLeft, joinRight:{}},...]
     */
    private List<Map<String, Object>> getJoinRowsWithRightDisplayName(List<Serializable> mainModelIds) {
        List<Map<String, Object>> joinRows = getJoinRows(mainModelIds);
        String joinRight = metaField.getJoinRight();
        List<Serializable> rightIds = joinRows.stream()
                .map(value -> (Serializable) value.get(joinRight))
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(rightIds)) {
            return Collections.emptyList();
        }
        Map<Serializable, String> displayNames = ReflectTool.getDisplayNames(metaField.getRelatedModel(), rightIds);
        if (ConvertType.DISPLAY.equals(flexQuery.getConvertType())) {
            joinRows.forEach(row -> {
                Serializable rightId = (Serializable) row.get(joinRight);
                row.put(joinRight, displayNames.get(rightId));
            });
        } else if (ConvertType.REFERENCE.equals(flexQuery.getConvertType())) {
            joinRows.forEach(row -> {
                Serializable rightId = (Serializable) row.get(joinRight);
                if (displayNames.containsKey(rightId)) {
                    row.put(joinRight, ModelReference.of(rightId, displayNames.get(rightId)));
                }
            });
        }
        return joinRows;
    }

    /**
     * Query the join model and right model according to the mainModelIds.
     * By default, the `joinRight` value is converted to ModelReference object.
     *
     * @param mainModelIds Main model ids
     * @return Join model rows: [{id, joinLeft, joinRight:{}},...]
     */
    private List<Map<String, Object>> getJoinRowsWithRightModelData(List<Serializable> mainModelIds) {
        List<Map<String, Object>> joinRows = getJoinRows(mainModelIds);
        String joinRight = metaField.getJoinRight();
        List<Serializable> rightIds = joinRows.stream()
                .map(value -> (Serializable) value.get(joinRight))
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(rightIds)) {
            return Collections.emptyList();
        }
        // Execute subQuery on the right model.
        List<Map<String, Object>> rightRows = this.getRightRows(metaField.getRelatedModel(), rightIds);
        // Group the right model rows by id
        Map<Serializable, Map<String, Object>> rightRowMap = rightRows.stream()
                .collect(Collectors.toMap(row -> (Serializable) row.get(ModelConstant.ID), row -> row));
        // Update the `joinRight` value of the join model row, to {joinRight: {right model row}}
        joinRows.forEach(r -> {
            Serializable rightId = (Serializable) r.get(joinRight);
            r.put(joinRight, rightRowMap.getOrDefault(rightId, null));
        });
        return joinRows;
    }

    /**
     * Query the join model according to the mainModelIds.
     * Since `joinLeft` and `joinRight` are both foreign key fields, this query directly get the id values
     * of the two fields in the database.
     *
     * @param mainModelIds Main model ids
     * @return Join model rows: [[id, joinLeft, joinRight], ...]
     */
    private List<Map<String, Object>> getJoinRows(List<Serializable> mainModelIds) {
        String joinLeft = metaField.getJoinLeft();
        String joinRight = metaField.getJoinRight();
        Filters joinFilters = Filters.of(joinLeft, Operator.IN, mainModelIds);
        Set<String> joinModelFields = Sets.newHashSet(joinLeft, joinRight);
        FlexQuery joinModelFlexQuery = new FlexQuery(joinModelFields, joinFilters);
        return ReflectTool.searchList(metaField.getJoinModel(), joinModelFlexQuery);
    }

    /**
     * Perform a subQuery on the right model
     *
     * @param rightModel right model
     * @param ids right model ids
     * @return right model rows
     */
    private List<Map<String, Object>> getRightRows(String rightModel, List<Serializable> ids) {
        Filters filters = new Filters().in(ModelConstant.ID, ids);
        FlexQuery rightFlexQuery;
        if (subQuery == null) {
            rightFlexQuery = new FlexQuery(Collections.emptyList(), filters);
        } else {
            // When there is a subQuery filters, merge them with `AND` logic
            filters.and(subQuery.getFilters());
            rightFlexQuery = new FlexQuery(subQuery.getFields(), filters, subQuery.getOrders());
            if (!CollectionUtils.isEmpty(subQuery.getFields())) {
                rightFlexQuery.getFields().add(ModelConstant.ID);
            }
            if (!CollectionUtils.isEmpty(subQuery.getSubQueries())) {
                SubQueries subQueries = new SubQueries();
                subQueries.setQueryMap(subQuery.getSubQueries());
                rightFlexQuery.setSubQueries(subQueries);
            }
        }
        rightFlexQuery.setConvertType(flexQuery.getConvertType());
        return ReflectTool.searchList(rightModel, rightFlexQuery);
    }

    /**
     * Group the expanded join model rows by the `joinLeft` attribute of the ManyToMany field,
     * which stores the main model id.
     *
     * @param expandedJoinRows join model rows, expanded with right model data
     * @return Grouped join model data: {mainModelId: [ModelReference, ...]}
     *      or {mainModelId: [{right row}, ...]
     */
    private Map<Serializable, List<Object>> groupJoinRows(List<Map<String, Object>> expandedJoinRows) {
        String joinLeft = metaField.getJoinLeft();
        String joinRight = metaField.getJoinRight();
        return expandedJoinRows.stream()
                // Filter out the row which rightId does not exist in the right model, which might be deleted.
                .filter(row -> row.get(joinRight)!= null)
                .collect(Collectors.groupingBy(
                        row -> (Serializable) row.get(joinLeft),
                        Collectors.mapping(row -> row.get(joinRight), Collectors.toList())));
    }
}
