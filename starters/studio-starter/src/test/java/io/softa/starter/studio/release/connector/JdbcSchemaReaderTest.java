package io.softa.starter.studio.release.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.exception.ExternalException;
import io.softa.starter.studio.release.desired.DesignRows;

/**
 * {@link JdbcSchemaReader} reverse-engineers a physical schema into
 * {@link DesignRows} via {@code DatabaseMetaData} — exercised against a real in-memory H2 database
 * (tables → models, columns → fields with reversed types; no optionSets; physical names inverted to
 * logical via Pascal/camel).
 */
class JdbcSchemaReaderTest {

    private static final String URL = "jdbc:h2:mem:p34_reader;DB_CLOSE_DELAY=-1";

    private final JdbcSchemaReader reader = new JdbcSchemaReader();

    @BeforeAll
    static void seedSystemConfigAndSchema() throws SQLException {
        if (SystemConfig.env == null) {
            SystemConfig.env = new SystemConfig();   // ExternalException construction reaches I18n
        }
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement st = c.createStatement()) {
            // Quote identifiers so H2 preserves the lowercase snake_case names (H2 upper-cases unquoted
            // identifiers; real MySQL keeps them lowercase) — keeps the reverse deterministic.
            st.execute("CREATE TABLE \"customer_order\" ("
                    + "\"id\" BIGINT PRIMARY KEY, "
                    + "\"order_no\" VARCHAR(64), "
                    + "\"amount\" DECIMAL(12,2), "
                    + "\"created_on\" TIMESTAMP)");
            st.execute("CREATE TABLE \"widget\" (\"id\" INT PRIMARY KEY, \"label\" VARCHAR(50))");
        }
    }

    private static Optional<Map<String, Object>> field(DesignRows rows, String modelName, String fieldName) {
        return rows.fields().stream()
                .filter(f -> modelName.equals(f.get("modelName")) && fieldName.equals(f.get("fieldName")))
                .findFirst();
    }

    @Test
    @DisplayName("tables → models (PascalCase), columns → fields (camelCase) with reversed types")
    void reverseEngineersTablesAndColumns() {
        DesignRows rows = reader.read(URL, "sa", "");

        // Two user tables → two models, named PascalCase from the snake_case table names.
        assertTrue(rows.models().stream().anyMatch(m -> "CustomerOrder".equals(m.get("modelName"))
                && "customer_order".equals(m.get("tableName"))));
        assertTrue(rows.models().stream().anyMatch(m -> "Widget".equals(m.get("modelName"))));

        // VARCHAR(64) → STRING carrying its width; column → camelCase field name.
        Map<String, Object> orderNo = field(rows, "CustomerOrder", "orderNo").orElseThrow();
        assertEquals("STRING", orderNo.get("fieldType"));
        assertEquals("order_no", orderNo.get("columnName"));
        assertEquals(64, orderNo.get("length"));

        // DECIMAL(12,2) → BIG_DECIMAL carrying precision + scale.
        Map<String, Object> amount = field(rows, "CustomerOrder", "amount").orElseThrow();
        assertEquals("BIG_DECIMAL", amount.get("fieldType"));
        assertEquals(12, amount.get("length"));
        assertEquals(2, amount.get("scale"));

        // BIGINT → LONG; TIMESTAMP → DATE_TIME.
        assertEquals("LONG", field(rows, "CustomerOrder", "id").orElseThrow().get("fieldType"));
        assertEquals("DATE_TIME", field(rows, "CustomerOrder", "createdOn").orElseThrow().get("fieldType"));

        // Physical schema has no logical enums / indexes-yet (P3.4 follow-up).
        assertTrue(rows.optionSets().isEmpty());
        assertTrue(rows.items().isEmpty());
        assertNotNull(rows.indexes());
    }

    @Test
    @DisplayName("two tables that reverse to the same modelName fail loud (no silent merge)")
    void collidingModelNamesFailLoud() throws SQLException {
        String url = "jdbc:h2:mem:p34_collision;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement st = c.createStatement()) {
            // Both `customer_order` and `customerOrder` derive the same modelName `CustomerOrder`.
            st.execute("CREATE TABLE \"customer_order\" (\"id\" BIGINT PRIMARY KEY)");
            st.execute("CREATE TABLE \"customerOrder\" (\"id\" BIGINT PRIMARY KEY)");
        }
        ExternalException ex = assertThrows(ExternalException.class, () -> reader.read(url, "sa", ""));
        assertTrue(ex.getMessage().contains("CustomerOrder"), ex.getMessage());
    }
}
