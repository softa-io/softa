package io.softa.starter.studio.release.connector;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.ExternalException;
import io.softa.framework.base.utils.StringTools;
import io.softa.starter.studio.release.desired.DesignRows;
import lombok.extern.slf4j.Slf4j;

/**
 * Reverse-engineers an external JDBC database's physical schema into {@link DesignRows} — the read half
 * of {@link JdbcSchemaConnector}. Reads via {@code DatabaseMetaData} (portable,
 * no per-DB SQL), turning each table into a model and each column into a field (its logical type via
 * {@link JdbcTypeReverse}).
 *
 * <p><b>Physical only</b>: produces <i>model / field</i> rows; <b>no optionSet/optionItem</b> (a physical
 * DB has no logical enums). The reversed rows carry only their business key (the derived
 * {@code modelName} / {@code fieldName}) — an import writes them into design under that key (P3.5). Naming
 * inverts the forward convention: {@code modelName =
 * PascalCase(tableName)}, {@code fieldName = camelCase(columnName)}, so a conventionally-named design
 * round-trips (a non-snake explicit {@code tableName} won't — the operator refines it).
 *
 * <p><b>Index reverse is deferred</b> (a P3.4 follow-up): produces empty indexes for now. Consequence:
 * publishing a design that declares indexes to a JDBC DB that already has them would re-emit CREATE INDEX;
 * acceptable for a fresh target / the reverse-into-design path, tracked for the incremental-publish case.
 */
@Slf4j
@Component
public class JdbcSchemaReader {

    /** Read {@code jdbcUrl}'s physical schema (the connected catalog/schema) into {@link DesignRows}. */
    public DesignRows read(String jdbcUrl, String username, String password) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            DatabaseMetaData meta = connection.getMetaData();
            String catalog = connection.getCatalog();
            String schema = connection.getSchema();
            List<Map<String, Object>> models = new ArrayList<>();
            List<Map<String, Object>> fields = new ArrayList<>();
            // modelName is derived (PascalCase of the camelCased table), so distinct tables can collapse to
            // one key (e.g. `foo_bar` and `fooBar` both → `FooBar`). Fail loud rather than silently merge
            // their columns into one model / overwrite on import.
            Map<String, String> tableByModelName = new HashMap<>();
            for (String table : tableNames(meta, catalog, schema)) {
                String modelName = StringUtils.capitalize(StringTools.toCamelCase(table));
                String priorTable = tableByModelName.putIfAbsent(modelName, table);
                if (priorTable != null) {
                    throw new ExternalException(
                            "JDBC reverse: tables {0} and {1} both map to model name {2}; "
                                    + "rename one before importing this schema.", priorTable, table, modelName);
                }
                Map<String, Object> model = new HashMap<>();
                model.put("modelName", modelName);
                model.put("tableName", table);
                models.add(model);
                readColumns(meta, catalog, schema, table, modelName, fields);
            }
            log.info("JDBC reverse: read {} table(s), {} column(s) from {}.", models.size(), fields.size(), jdbcUrl);
            // No optionSets / items — physical schema has no logical enums. Indexes deferred (P3.4 follow-up).
            return new DesignRows(models, fields, List.of(), List.of(), List.of());
        } catch (SQLException e) {
            log.error("JDBC schema read failed against {}.", jdbcUrl, e);
            throw new ExternalException("JDBC schema read failed against {0}: {1}", jdbcUrl, e.getMessage());
        }
    }

    private List<String> tableNames(DatabaseMetaData meta, String catalog, String schema) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                names.add(rs.getString("TABLE_NAME"));
            }
        }
        return names;
    }

    private void readColumns(DatabaseMetaData meta, String catalog, String schema, String table,
                             String modelName, List<Map<String, Object>> fields) throws SQLException {
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String columnName = rs.getString("COLUMN_NAME");
                ReversedColumn reversed = JdbcTypeReverse.reverse(
                        rs.getInt("DATA_TYPE"),
                        nullableInt(rs, "COLUMN_SIZE"), nullableInt(rs, "DECIMAL_DIGITS"));
                Map<String, Object> field = new HashMap<>();
                field.put("modelName", modelName);
                field.put("fieldName", StringTools.toCamelCase(columnName));
                field.put("columnName", columnName);
                field.put("fieldType", reversed.fieldType().name());
                if (reversed.length() != null) {
                    field.put("length", reversed.length());
                }
                if (reversed.scale() != null) {
                    field.put("scale", reversed.scale());
                }
                fields.add(field);
            }
        }
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
