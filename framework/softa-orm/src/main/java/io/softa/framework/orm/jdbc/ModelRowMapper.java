package io.softa.framework.orm.jdbc;

import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.JdbcUtils;

/**
 * JDBCTemplate Map structure row data encapsulation.
 * To convert the underlined-separated database column key to camel case field name. e.g. dept_id -> deptId.
 */
public class ModelRowMapper implements RowMapper<Map<String, Object>> {

    private final String modelName;

    public ModelRowMapper(String modelName) {
        this.modelName = modelName;
    }

    /**
     * @param rs the ResultSet to map (pre-initialized for the current row)
     * @param rowNum the number of the current row
     * @return LinkedHashMap
     */
    @Override
    public Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData metaData = rs.getMetaData();
        int columnCount = metaData.getColumnCount();
        Map<String, Object> resultMap = new LinkedHashMap<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String columnName = JdbcUtils.lookupColumnName(metaData, i);
            // Convert the column name to field name
            String fieldName;
            if (ModelConstant.SYSTEM_MODEL.contains(modelName)) {
                fieldName = StringTools.toCamelCase(columnName);
            } else {
                Optional<MetaField> field = ModelManager.getFieldByColumnName(modelName, columnName);
                if (field.isPresent()) {
                    fieldName = field.get().getFieldName();
                } else {
                    fieldName = columnName;
                }
            }
            Object value = JdbcUtils.getResultSetValue(rs, i);
            if (value instanceof java.sql.Date sqlDate) {
                // Auto-convert java.sql.Date to LocalDate
                value = sqlDate.toLocalDate();
            }
            resultMap.put(fieldName, value);
        }
        return resultMap;
    }
}
