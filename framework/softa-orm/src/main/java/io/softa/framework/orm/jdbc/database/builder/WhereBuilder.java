package io.softa.framework.orm.jdbc.database.builder;

import java.util.*;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterControl;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.jdbc.database.SqlWrapper;
import io.softa.framework.orm.jdbc.database.parser.FilterUnitParser;
import io.softa.framework.orm.jdbc.database.parser.FilterXToManyParser;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * WHERE clause builder
 */
public class WhereBuilder extends BaseBuilder implements SqlClauseBuilder {

    /** Field types a typed whole number can be matched against exactly. */
    private static final Set<FieldType> WHOLE_NUMBER_TYPES = EnumSet.of(FieldType.INTEGER, FieldType.LONG);

    private static final Set<Operator> TEXT_MATCH_OPERATORS =
            EnumSet.of(Operator.CONTAINS, Operator.START_WITH, Operator.EQUAL);

    private static final Set<Operator> NEGATED_TEXT_MATCH_OPERATORS =
            EnumSet.of(Operator.NOT_CONTAINS, Operator.NOT_START_WITH, Operator.NOT_EQUAL);

    public WhereBuilder(SqlWrapper sqlWrapper, FlexQuery flexQuery) {
        super(sqlWrapper, flexQuery);
    }

    public void build() {
        // SoftDelete filter processing
        Filters filters = this.handleSoftDeleted(flexQuery.getFilters());
        // Access control filter processing
        filters = this.handleActiveControl(filters);
        // Multi-tenant model, add tenant filtering conditions
        filters = this.handleMultiTenant(filters);
        if (!Filters.isEmpty(filters)) {
            sqlWrapper.where(handleFilters(filters));
        }
    }

    /**
     * Append a `deleted = false` predicate when the model is soft-deleted.
     * Two opt-out paths, both honored:
     *   - Programmatic: {@link FilterControl#bypassSoftDelete()} on the FlexQuery
     *   - Declarative: caller already references the soft-delete field in `filters`
     *     (e.g. an admin "show deleted" UI passing `deleted = true`)
     * Tenant isolation and permission filtering still apply in both cases.
     *
     * @param filters original filters
     * @return processed filters
     */
    private Filters handleSoftDeleted(Filters filters) {
        if (flexQuery.getFilterControl().isSkipSoftDelete()) {
            return filters;
        }
        if (!ModelManager.isSoftDeleted(mainModelName)) {
            return filters;
        }
        if (Filters.containsField(filters, ModelConstant.SOFT_DELETED_FIELD)) {
            return filters;
        }
        Filters deletedFilters = new Filters().eq(ModelConstant.SOFT_DELETED_FIELD, false);
        return Filters.and(filters, deletedFilters);
    }

    /**
     * Append an `active = true` predicate when the model has active control.
     * Two opt-out paths, both honored:
     *   - Programmatic: {@link FilterControl#bypassActiveControl()} on the FlexQuery
     *   - Declarative: caller already references the `active` field in `filters`
     *     (e.g. an admin "show inactive" UI passing `active = false`)
     * Tenant isolation and permission filtering still apply in both cases.
     *
     * @param filters original filters
     * @return processed filters
     */
    private Filters handleActiveControl(Filters filters) {
        if (flexQuery.getFilterControl().isSkipActiveControl()) {
            return filters;
        }
        if (!ModelManager.isActiveControl(mainModelName)) {
            return filters;
        }
        if (Filters.containsField(filters, ModelConstant.ACTIVE_CONTROL_FIELD)) {
            return filters;
        }
        Filters activeControlFilter = new Filters().eq(ModelConstant.ACTIVE_CONTROL_FIELD, true);
        return Filters.and(filters, activeControlFilter);
    }

    /**
     * Handle tenant filtering conditions for multi-tenant models.
     *
     * @param filters original filters
     * @return processed filters
     */
    private Filters handleMultiTenant(Filters filters) {
        if (!ModelManager.isMultiTenantControl(mainModelName)) {
            // The model is not multi-tenant, return directly
            return filters;
        }
        // Add tenant filtering conditions
        Long tenantId = ContextHolder.getContext().getTenantId();
        Filters tenantFilter = new Filters().eq(ModelConstant.TENANT_ID, tenantId);
        return Filters.and(filters, tenantFilter);
    }

    /**
     * If the field chain contains OneToMany or ManyToMany fields, construct the FlexQuery of the associated model,
     * then, call the search method to return the ids of the left model.
     * The final query condition is [id, in, ids].
     * For example,
     *      [deptId.empIds, in, [1,2,3]] is converted to [deptId.id, in, [4,5,6]], exchange empIds with deptIds.
     *
     * @param filters filters
     * @return SQL condition of where clause
     */
    private StringBuilder handleFilters(Filters filters) {
        if (FilterType.LEAF.equals(filters.getType())) {
            // For LEAF type, only one FilterUnit, which may be XToMany field, directly process the conditions of this layer.
            FilterXToManyParser xToManyBuilder = new FilterXToManyParser(mainModelName, sqlWrapper, filters.getLogicOperator());
            StringBuilder sqlBuilder = this.parseFilterUnit(filters.getFilterUnit(), xToManyBuilder);
            if (!xToManyBuilder.isEmpty()) {
                // xToManyBuilder is not empty, construct XToMany field query conditions
                sqlBuilder.append(xToManyBuilder.parse());
            }
            return sqlBuilder;
        } else if (FilterType.TREE.equals(filters.getType())) {
            return handleFiltersTree(filters);
        }
        throw new BusinessException("Filters property exception! {0}", filters);
    }

    /**
     * Handle the TREE Filters recursively.
     *
     * @param filters TREE filters object
     * @return SQL condition of where clause
     */
    private StringBuilder handleFiltersTree(Filters filters) {
        // Current level filter information
        FilterXToManyParser xToManyBuilder = new FilterXToManyParser(mainModelName, sqlWrapper, filters.getLogicOperator());
        StringBuilder sqlBuilder = new StringBuilder();
        String logic = filters.getLogicOperator().name();
        for (Filters child : filters.getChildren()) {
            StringBuilder childSql = processChildFilter(child, xToManyBuilder);
            if (!sqlBuilder.isEmpty() && !childSql.isEmpty()) {
                // Add logical operator `AND` or `OR` for the same level of filters.
                sqlBuilder.append(" ").append(logic).append(" ");
            }
            sqlBuilder.append(childSql);
        }
        // Construct XToMany field query conditions based on xToManyBuilder
        if (!xToManyBuilder.isEmpty()) {
            StringBuilder xToMSql = xToManyBuilder.parse();
            if (!sqlBuilder.isEmpty() && !xToMSql.isEmpty()) {
                sqlBuilder.append(" ").append(logic).append(" ");
            }
            sqlBuilder.append(xToMSql);
        }
        return sqlBuilder;
    }

    /**
     * Process a child node of the filters object, which may be LEAF type or TREE type.
     *
     * @param child child node of filters
     * @param xToManyBuilder XToMany query condition builder at the same level
     * @return SQL condition fragment of a child node
     */
    private StringBuilder processChildFilter(Filters child, FilterXToManyParser xToManyBuilder) {
        if (FilterType.LEAF.equals(child.getType())) {
            // When a child is a LEAF node, get the filterUnit object
            return this.parseFilterUnit(child.getFilterUnit(), xToManyBuilder);
        } else {
            // When child is a TREE node, recursively process, get the SQL fragment of child node, and wrap it in ()
            StringBuilder treeSql = handleFiltersTree(child);
            if (!treeSql.isEmpty()) {
                return new StringBuilder("(").append(treeSql).append(")");
            }
        }
        return new StringBuilder();
    }

    /**
     * Parse FilterUnit to generate SQL condition fragment.
     *
     * @param filterUnit Single filter unit
     * @param xToManyBuilder XToMany query condition builder at the same level.
     */
    private StringBuilder parseFilterUnit(FilterUnit filterUnit, FilterXToManyParser xToManyBuilder) {
        if (filterUnit.isTuple()) {
            return this.parseTupleFilterUnit(filterUnit);
        }
        String logicField = filterUnit.getField();
        if (ModelConstant.SEARCH_NAME.equals(logicField)) {
            // Search by main model `searchName` directly.
            return this.parseSearchName(filterUnit, xToManyBuilder);
        } else if (logicField.contains(".")) {
            // Cascade field processing `f0.f1.f2.f3`
            return this.parseCascadeField(filterUnit, xToManyBuilder);
        }
        MetaField metaField = ModelManager.getModelField(mainModelName, logicField);
        if (StringUtils.isNotBlank(metaField.getCascadedField())) {
            // Set the field of filterUnit to the cascaded attribute of metaField,
            filterUnit.setField(metaField.getCascadedField());
            return this.parseCascadeField(filterUnit, xToManyBuilder);
        } else {
            // Single model field processing
            sqlWrapper.accessModelField(mainModelName, logicField);
            if (FieldType.TO_MANY_TYPES.contains(metaField.getFieldType())) {
                // XToMany field processing, such as `["projectIds", "IN", [20]]`, execute `id IN (20)` on the right model.
                FilterUnit newUnit = new FilterUnit(ModelConstant.ID, filterUnit.getOperator(), filterUnit.getValue());
                xToManyBuilder.addXToManyFilterUnit(SqlWrapper.MAIN_TABLE_ALIAS, metaField, newUnit);
                return new StringBuilder();
            } else {
                // Non-cascade field processing: `t.column = ?`
                this.convertFilterUnit(metaField, filterUnit);
                return FilterUnitParser.parse(sqlWrapper, SqlWrapper.MAIN_TABLE_ALIAS, metaField, filterUnit);
            }
        }
    }

    private StringBuilder parseTupleFilterUnit(FilterUnit filterUnit) {
        List<ResolvedTupleField> resolvedFields = filterUnit.getFields().stream()
                .map(this::resolveTupleField)
                .toList();
        filterUnit.setValue(this.normalizeTupleValues(filterUnit, resolvedFields));
        return FilterUnitParser.parseTuple(sqlWrapper,
                resolvedFields.stream().map(ResolvedTupleField::fieldAlias).toList(),
                filterUnit);
    }

    private ResolvedTupleField resolveTupleField(String logicField) {
        Assert.notTrue(ModelConstant.SEARCH_NAME.equals(logicField),
                "Tuple filter does not support searchName field: {0}", logicField);
        if (logicField.contains(".")) {
            return this.resolveTupleCascadeField(logicField);
        }
        MetaField metaField = ModelManager.getModelField(mainModelName, logicField);
        if (StringUtils.isNotBlank(metaField.getCascadedField())) {
            return this.resolveTupleField(metaField.getCascadedField());
        }
        sqlWrapper.accessModelField(mainModelName, logicField);
        Assert.notTrue(FieldType.TO_MANY_TYPES.contains(metaField.getFieldType()),
                "Tuple filter does not support XToMany field: {0}", logicField);
        return new ResolvedTupleField(getFieldAlias(SqlWrapper.MAIN_TABLE_ALIAS, metaField), metaField);
    }

    private ResolvedTupleField resolveTupleCascadeField(String logicField) {
        List<String> cascadeFields = Arrays.asList(StringUtils.split(logicField, "."));
        Assert.isTrue(cascadeFields.size() - 1 <= BaseConstant.CASCADE_LEVEL,
                "Based on database performance considerations, the cascading query of model {0} cannot exceed the {1} level: {2}!",
                mainModelName, BaseConstant.CASCADE_LEVEL, logicField);
        String currentAlias = SqlWrapper.MAIN_TABLE_ALIAS;
        String currentModelName = mainModelName;
        MetaField metaField = null;
        for (int i = 0; i < cascadeFields.size(); i++) {
            String fieldName = cascadeFields.get(i);
            sqlWrapper.accessModelField(currentModelName, fieldName);
            metaField = ModelManager.getModelField(currentModelName, fieldName);
            if (FieldType.RELATED_TYPES.contains(metaField.getFieldType())) {
                Assert.notTrue(FieldType.TO_MANY_TYPES.contains(metaField.getFieldType()),
                        "Tuple filter does not support XToMany field: {0}", logicField);
                if (i < cascadeFields.size() - 1) {
                    String fieldChain = String.join(".", cascadeFields.subList(0, i + 1));
                    String rightAlias = sqlWrapper.getTableAlias().getRightTableAlias(fieldChain);
                    if (StringUtils.isBlank(rightAlias)) {
                        rightAlias = sqlWrapper.getTableAlias().generateRightTableAlias(fieldChain);
                        sqlWrapper.leftJoin(metaField, currentAlias, rightAlias, flexQuery.isAcrossTimeline());
                    }
                    currentAlias = rightAlias;
                    currentModelName = metaField.getRelatedModel();
                }
            } else {
                Assert.isTrue(i == cascadeFields.size() - 1,
                        "For the cascading query of model {0}, the field {1} in cascade string {2} is not a relational field!",
                        mainModelName, fieldName, logicField);
            }
        }
        return new ResolvedTupleField(getFieldAlias(currentAlias, metaField), metaField);
    }

    /**
     * Cascade field processing: f0.f1.f2.f3, construct a join query statement.
     *
     * @param filterUnit Single filter unit
     * @param xToManyBuilder XToMany query condition builder at the same level
     * @return SQL condition fragment of cascade field
     */
    private StringBuilder parseCascadeField(FilterUnit filterUnit, FilterXToManyParser xToManyBuilder) {
        List<String> cascadeFields = Arrays.asList(StringUtils.split(filterUnit.getField(), "."));
        Assert.notTrue(cascadeFields.contains(ModelConstant.SEARCH_NAME),
                "The model {0} does not support search with a cascading searchName: {1}", mainModelName, filterUnit.getField());
        int size = cascadeFields.size();
        Assert.isTrue(size - 1 <= BaseConstant.CASCADE_LEVEL,
                "Based on database performance considerations, the cascading query of model {0} cannot exceed the {1} level: {2}!",
                mainModelName, BaseConstant.CASCADE_LEVEL, filterUnit.getField());
        String currentAlias = SqlWrapper.MAIN_TABLE_ALIAS;
        String currentModelName = mainModelName;
        MetaField metaField = null;
        for (int i = 0; i < size; i++) {
            String fieldName = cascadeFields.get(i);
            sqlWrapper.accessModelField(currentModelName, fieldName);
            metaField = ModelManager.getModelField(currentModelName, fieldName);
            if (FieldType.RELATED_TYPES.contains(metaField.getFieldType())) {
                // The processing of relational fields
                if (FieldType.TO_MANY_TYPES.contains(metaField.getFieldType())) {
                    // Truncate OneToMany/ManyToMany fields.
                    // If there are still cascading fields behind, use the second half as the new FilterUnit field.
                    // If the current field is the last field, use "id" as the new FilterUnit field,
                    // which is the id field of the right model.
                    String fieldsOfManyEndpoint = i < size - 1 ?
                            String.join(".", cascadeFields.subList(i + 1, size)) : ModelConstant.ID;
                    FilterUnit newUnit = new FilterUnit(fieldsOfManyEndpoint, filterUnit.getOperator(), filterUnit.getValue());
                    xToManyBuilder.addXToManyFilterUnit(currentAlias, metaField, newUnit);
                    // XToMany fields are temporarily stored, and an empty StringBuilder object is returned directly,
                    // and the SQL is processed separately
                    return new StringBuilder();
                } else if (i < size - 1) {
                    // The Current xToOne field is not the last field, add it as a leftJoin condition.
                    String fieldChain = String.join(".", cascadeFields.subList(0, i + 1));
                    String rightAlias = sqlWrapper.getTableAlias().getRightTableAlias(fieldChain);
                    if (StringUtils.isBlank(rightAlias)) {
                        // If the associated table alias does not exist, generate a new alias and add a leftJoin condition.
                        rightAlias = sqlWrapper.getTableAlias().generateRightTableAlias(fieldChain);
                        sqlWrapper.leftJoin(metaField, currentAlias, rightAlias, flexQuery.isAcrossTimeline());
                    }
                    // The alias of the associated table for current xToOne field is used as the currentAlias for next loop
                    currentAlias = rightAlias;
                    // The associated model of current xToOne field is used as the currentModelName for next loop
                    currentModelName = metaField.getRelatedModel();
                }
            } else {
                // For non-relational fields, when i < size - 1, it means that there is a cascading query through non-relational fields.
                Assert.isTrue(i == size - 1,
                        "For the cascading query of model {0}, the field {1} in cascade string {2} is not a relational field!",
                        mainModelName, fieldName, filterUnit.getField());
                // Convert the filterUnit operator and query value for non-relational fields.
                this.convertFilterUnit(metaField, filterUnit);
            }
        }
        // When there is no XToMany field, get the field alias normally,
        // including the case where the last field is ManyToOne/OneToOne field.
        Assert.notTrue(metaField.isDynamic(),
                "The last field {0} of filter cascade {1} must be a stored field in model {2}!",
                metaField.getFieldName(), filterUnit.getField(), metaField.getModelName());
        return FilterUnitParser.parse(sqlWrapper, currentAlias, metaField, filterUnit);
    }

    /**
     * When using the virtual field `searchName` for searching,
     * it is automatically split into multiple fields for `OR` condition search.
     * Only the main mode supports using `searchName` for searching.
     *
     * @param filterUnit Single filter unit
     * @param xToManyBuilder XToMany query condition builder at the same level
     * @return `OR` SQL conditions based on searchName, such as (c1 or c2)
     */
    private StringBuilder parseSearchName(FilterUnit filterUnit, FilterXToManyParser xToManyBuilder) {
        List<String> searchNames = ModelManager.getModel(mainModelName).getSearchName();
        if (searchNames.isEmpty()) {
            searchNames = Collections.singletonList(ModelConstant.ID);
        }
        searchNames.forEach(f -> sqlWrapper.accessModelField(mainModelName, f));
        // Construct the `OR` condition search based on the `searchName` fields.
        Operator operator = filterUnit.getOperator();
        Object value = filterUnit.getValue();
        StringBuilder filterUnitSql = new StringBuilder();
        if (searchNames.size() == 1) {
            // When there is only one field configured in `searchName`, parse directly based on this field.
            filterUnitSql.append(parseSearchField(searchNames.getFirst(), operator, value, xToManyBuilder));
        } else {
            // When there are multiple fields configured in `searchName`,
            // split into multiple fields for `OR` condition search.
            filterUnitSql.append(" (");
            for (int i = 0; i < searchNames.size(); i++) {
                if (i > 0) {
                    filterUnitSql.append(" OR ");
                }
                filterUnitSql.append(parseSearchField(searchNames.get(i), operator, value, xToManyBuilder));
            }
            filterUnitSql.append(") ");
        }
        return filterUnitSql;
    }

    /**
     * One `searchName` member's condition.
     *
     * <p>A text member takes the operator as given. A non-text one cannot: a model with neither a
     * declared `searchName` nor a `name` field searches by `id`, and the box's `CONTAINS` became
     * `id LIKE '%...%'` — which PostgreSQL refuses outright for a BIGINT ("operator does not exist:
     * bigint ~~ unknown"), whatever was typed. Every list of such a model failed as soon as anyone
     * typed in its search box. So a whole number is matched exactly, the one search an id
     * supports, and any other input matches no row (its negation, every row) instead of failing.
     * Operators that are not about matching text keep their own meaning.
     */
    private StringBuilder parseSearchField(String fieldName, Operator operator, Object value,
                                           FilterXToManyParser xToManyBuilder) {
        FieldType fieldType = ModelManager.getModelField(mainModelName, fieldName).getFieldType();
        boolean textMatch = TEXT_MATCH_OPERATORS.contains(operator);
        boolean textMatchNegated = NEGATED_TEXT_MATCH_OPERATORS.contains(operator);
        if (FieldType.TEXT_SEARCH_TYPES.contains(fieldType) || !(textMatch || textMatchNegated)) {
            return parseFilterUnit(new FilterUnit(fieldName, operator, value), xToManyBuilder);
        }
        Long wholeNumber = WHOLE_NUMBER_TYPES.contains(fieldType) ? parseWholeNumber(value) : null;
        if (wholeNumber != null) {
            Operator exact = textMatch ? Operator.EQUAL : Operator.NOT_EQUAL;
            return parseFilterUnit(new FilterUnit(fieldName, exact, wholeNumber), xToManyBuilder);
        }
        return new StringBuilder(textMatch ? " 1 = 0 " : " 1 = 1 ");
    }

    private static Long parseWholeNumber(Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        String text = value == null ? "" : value.toString().trim();
        if (!text.matches("-?\\d{1,18}")) {
            return null;
        }
        return Long.parseLong(text);
    }

    private List<List<Object>> normalizeTupleValues(FilterUnit filterUnit, List<ResolvedTupleField> resolvedFields) {
        List<List<Object>> normalizedTuples = new ArrayList<>();
        for (Object tupleValue : (Collection<?>) filterUnit.getValue()) {
            List<?> tuple = (List<?>) tupleValue;
            List<Object> normalizedTuple = new ArrayList<>(tuple.size());
            for (int i = 0; i < tuple.size(); i++) {
                normalizedTuple.add(convertTupleValue(resolvedFields.get(i).metaField(), tuple.get(i)));
            }
            normalizedTuples.add(normalizedTuple);
        }
        return normalizedTuples;
    }

    private Object convertTupleValue(MetaField metaField, Object value) {
        if (value == null) {
            return null;
        }
        if (metaField.isEncrypted() && value instanceof String strValue) {
            return EncryptUtils.encrypt(strValue);
        }
        return value;
    }

    private String getFieldAlias(String tableAlias, MetaField metaField) {
        if (metaField.isTranslatable()) {
            return sqlWrapper.filterTranslatableField(tableAlias, metaField);
        }
        return tableAlias + "." + metaField.getColumnName();
    }

    /**
     * Convert the operator and value of the filterUnit based on the field type for non-relational fields.
     *
     * @param metaField field object
     * @param filterUnit filterUnit
     */
    private void convertFilterUnit(MetaField metaField, FilterUnit filterUnit) {
        // Skip directly if the value is null
        if (filterUnit.getValue() == null) {
            return;
        }
        if (metaField.isEncrypted()) {
            // If the current field is an encrypted field, encrypt the search value,
            // and reset the operator to "=" or "!=", for accurate search.
            Operator operator = filterUnit.getOperator();
            Object value = filterUnit.getValue();
            if (Operator.NOT_CONTAINS.equals(operator) || Operator.NOT_START_WITH.equals(operator)) {
                operator = Operator.NOT_EQUAL;
            } else if (Operator.CONTAINS.equals(operator) || Operator.START_WITH.equals(operator)) {
                operator = Operator.EQUAL;
            }
            if (value instanceof String strValue) {
                value = EncryptUtils.encrypt(strValue);
            } else if (value instanceof Collection<?> collectionValue) {
                value = collectionValue.stream().filter(v -> StringUtils.isNotBlank((String) v))
                        .map(v -> EncryptUtils.encrypt((String) v)).collect(Collectors.toList());
            }
            filterUnit.setOperator(operator);
            filterUnit.setValue(value);
        } else {
            formatFilterUnitValue(filterUnit);
        }
    }

    private void formatFilterUnitValue(FilterUnit filterUnit) {
        Object obj = filterUnit.getValue();
        if (obj instanceof Enum<?> enumValue) {
            obj = JsonUtils.getMapper().convertValue(enumValue, String.class);
        } else if (obj instanceof Collection<?> collectionValue) {
            obj = collectionValue.stream().map(v -> {
                if (v instanceof Enum<?> vEnum) {
                    return JsonUtils.getMapper().convertValue(vEnum, String.class);
                }
                return v;
            }).collect(Collectors.toList());
        }
        filterUnit.setValue(obj);
    }

    private record ResolvedTupleField(String fieldAlias, MetaField metaField) {}
}
