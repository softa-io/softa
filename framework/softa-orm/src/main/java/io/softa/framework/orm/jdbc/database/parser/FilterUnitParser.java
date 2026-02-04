package io.softa.framework.orm.jdbc.database.parser;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import io.softa.framework.base.constant.EnvConstant;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.EmpInfo;
import io.softa.framework.base.enums.Operator;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.jdbc.database.DBUtil;
import io.softa.framework.orm.jdbc.database.SqlWrapper;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * FilterUnit parser to get the SQL condition.
 */
public class FilterUnitParser {

    /**
     * Build the SQL fragment based on the calculated field alias(table alias + column name)
     *
     * @param sqlWrapper SQL wrapper
     * @param tableAlias table alias of current filterUnit field
     * @param metaField field object corresponding to the current filterUnit
     * @param filterUnit filterUnit
     */
    public static StringBuilder parse(SqlWrapper sqlWrapper, String tableAlias, MetaField metaField, FilterUnit filterUnit) {
        String fieldAlias;
        if (metaField.isTranslatable()) {
            // Replace the field name with the translated field name in WHERE clause
            fieldAlias = sqlWrapper.filterTranslatableField(tableAlias, metaField);
        } else {
            fieldAlias = tableAlias + "." + metaField.getColumnName();
        }
        StringBuilder condition = new StringBuilder(fieldAlias);
        Operator operator = filterUnit.getOperator();
        Object value = filterUnit.getValue();
        switch (operator) {
            case EQUAL:
            case NOT_EQUAL:
            case GREATER_THAN:
            case GREATER_THAN_OR_EQUAL:
            case LESS_THAN:
            case LESS_THAN_OR_EQUAL:
                condition.append(" ").append(DBUtil.getPredicate(operator));
                if (value instanceof String expression && StringTools.isReservedField(expression)) {
                    // Handle field comparison, value is @{fieldName}, which is a reserved field name,
                    // embed it into SQL after checking the field name is legal
                    String fieldName = expression.substring(2, expression.length() - 1).trim();
                    String columnName = ModelManager.getModelFieldColumn(metaField.getModelName(), fieldName);
                    condition.append(" ").append(tableAlias).append(".").append(columnName).append(" ");
                } else {
                    if (value instanceof String v && EnvConstant.ENV_PARAMS.contains(v.toUpperCase())) {
                        value = convertEnvParameter(v);
                    }
                    condition.append(" ?");
                    sqlWrapper.addArgValue(value);
                }
                break;
            case CONTAINS:
            case NOT_CONTAINS:
                condition.append(" ").append(DBUtil.getPredicate(operator)).append(" ?");
                sqlWrapper.addArgValue("%" + value + "%");
                break;
            case START_WITH:
            case NOT_START_WITH:
                condition.append(" ").append(DBUtil.getPredicate(operator)).append(" ?");
                value = value + "%";
                sqlWrapper.addArgValue(value);
                break;
            case IN:
            case NOT_IN:
                condition.append(" ").append(DBUtil.getPredicate(operator)).append(" (");
                StringBuilder inSql = new StringBuilder();
                if (value instanceof Collection<?> objects) {
                    objects.forEach(o -> {
                        inSql.append("?,");
                        sqlWrapper.addArgValue(o);
                    });
                }
                StringTools.removeLastComma(inSql);
                condition.append(inSql).append(")");
                break;
            case BETWEEN:
            case NOT_BETWEEN:
                condition.append(" ").append(DBUtil.getPredicate(operator)).append(" ");
                sqlWrapper.addArgValue(((List<?>) value).get(0));
                sqlWrapper.addArgValue(((List<?>) value).get(1));
                break;
            case IS_SET:
            case IS_NOT_SET:
                condition.append(" ").append(DBUtil.getPredicate(operator));
                break;
            case PARENT_OF:
                condition = parseParentOf(sqlWrapper, tableAlias, operator, (Collection<?>) value);
                break;
            case CHILD_OF:
                condition = parseChildOf(sqlWrapper, fieldAlias, operator, (Collection<?>) value);
                break;
            default:
                throw new IllegalArgumentException("FilterUnitParser currently does not support the operator {0}! ", operator.getName());
        }
        return condition;
    }

    /**
     * Convert the env parameter to the corresponding value.
     * @param value env parameter
     * @return the corresponding value
     */
    private static Object convertEnvParameter(String value) {
        Context context = ContextHolder.getContext();
        if (EnvConstant.USER_ID.equals(value)) {
            return context.getUserId();
        } else if (EnvConstant.EMP_INFO_PARAMS.contains(value) && context.getEmpInfo() != null) {
            EmpInfo empInfo = context.getEmpInfo();
            return switch (value) {
                case EnvConstant.USER_EMP_ID -> empInfo.getEmpId();
                case EnvConstant.USER_POSITION_ID -> empInfo.getPositionId();
                case EnvConstant.USER_DEPT_ID -> empInfo.getDeptId();
                case EnvConstant.USER_COMP_ID -> empInfo.getCompanyId();
                default -> throw new IllegalArgumentException("Not support the env parameter {0}! ", value);
            };
        } else {
            return switch (value) {
                case EnvConstant.NOW -> LocalDateTime.now();
                case EnvConstant.TODAY -> LocalDate.now();
                default -> throw new IllegalArgumentException("Not support the env parameter {0}! ", value);
            };
        }
    }

    /**
     * Parse the PARENT_OF filter condition.
     * When the value of PARENT_OF is a single value, the IN condition is constructed by splitting the idPath
     * separated by "/". which is: id IN split(idPath, "/")
     * <p>
     * When the value of PARENT_OF is a list, the IN condition is constructed by combining the id set extracted from
     * the idPath separated by "/". which is: id IN Set(split(idPath1, "/") + split(idPath2, "/") + split(idPath3, "/"))
     *
     * @param sqlWrapper SQL wrapper
     * @param tableAlias table alias
     * @param operator operator
     * @param idPaths idPaths
     * @return SQL condition
     */
    private static StringBuilder parseParentOf(SqlWrapper sqlWrapper, String tableAlias, Operator operator, Collection<?> idPaths) {
        StringBuilder condition = new StringBuilder(tableAlias).append(".").append(ModelConstant.ID).append(" ").append(DBUtil.getPredicate(operator)).append(" (");
        // Extract the id set from the idPath separated by "/"
        Set<Long> parentIds = new HashSet<>();
        for (Object idPath : idPaths) {
            parentIds.addAll(StringTools.splitIdPath((String) idPath));
        }
        // Build the IN condition
        StringBuilder inSql = new StringBuilder();
        (parentIds).forEach( v -> {
            inSql.append("?,");
            sqlWrapper.addArgValue(v);
        });
        StringTools.removeLastComma(inSql);
        condition.append(inSql).append(")");
        return condition;
    }

    /**
     * Parse the CHILD_OF filter condition.
     * When the value of CHILD_OF is a single value, the StartWith condition is constructed,
     * which equals to `t.field LIKE ?, value%`, that can use the prefix index.
     * <p>
     * When the value of CHILD_OF is a list, the OR condition is constructed by combining the StartWith condition,
     * which is: `(t.field LIKE ? OR t.field LIKE ?), value1%, value2%`
     *
     * @param sqlWrapper SQL wrapper
     * @param fieldAlias field alias
     * @param operator operator
     * @param idPaths idPaths
     * @return SQL condition
     */
    private static StringBuilder parseChildOf(SqlWrapper sqlWrapper, String fieldAlias, Operator operator, Collection<?> idPaths) {
        StringBuilder condition;
        int size = idPaths.size();
        if (size == 1) {
            return buildStartWithCondition(sqlWrapper, fieldAlias, operator, (String) idPaths.iterator().next());
        } else if (size > 1) {
            condition = new StringBuilder(" (");
            Iterator<?> iterator = idPaths.iterator();
            for (int i = 0; i < size; i++) {
                if (i > 0) {
                    condition.append(" OR ");
                }
                condition.append(buildStartWithCondition(sqlWrapper, fieldAlias, operator, (String) iterator.next()));
            }
            condition.append(")");
        } else {
            throw new IllegalArgumentException("The idPaths of [{0}, {1}, {2}] cannot be empty!", fieldAlias, operator, idPaths);
        }
        return condition;
    }

    /**
     * Use StartWith to construct a simple query condition,
     * which can use the prefix index: `t.f LIKE ?, idPath%`
     * @param sqlWrapper SQL wrapper
     * @param fieldAlias field alias
     * @param operator operator
     * @param idPath idPath
     */
    private static StringBuilder buildStartWithCondition(SqlWrapper sqlWrapper, String fieldAlias, Operator operator, String idPath) {
        StringBuilder condition = new StringBuilder(fieldAlias).append(" ").append(DBUtil.getPredicate(operator)).append(" ?");
        idPath = idPath + "%";
        sqlWrapper.addArgValue(idPath);
        return condition;
    }
}
