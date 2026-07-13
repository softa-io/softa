package io.softa.starter.studio.release.desired;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.ddl.dialect.MySqlDdlDialect;
import io.softa.starter.studio.release.ddl.impl.MetadataChangeDdlRendererImpl;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.ddl.TestMetadataResolver;

/**
 * The load-bearing contract (map risk R3): the design↔design diff's output, fed straight
 * into {@link MetadataChangeDdlRendererImpl}, produces rename-aware DDL. A renamed column must become
 * {@code CHANGE COLUMN} — never {@code DROP COLUMN} + {@code ADD COLUMN}, which would silently divorce
 * the data. This pins the whole differ → DDL seam end-to-end, including the JSON normalization that
 * turns an enum {@code fieldType} on the workspace side into the code string the env baseline stores.
 */
class DesignAggregateDifferDdlGoldenTest {

    private final DesignAggregateDiffer differ = new DesignAggregateDiffer();
    private final MetadataChangeDdlRendererImpl renderer = new MetadataChangeDdlRendererImpl();
    private final DdlDialect mysql = new MySqlDdlDialect(TestMetadataResolver.DDL);

    private static Map<String, Object> model(long id, String name, String table) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("modelName", name);
        m.put("tableName", table);
        m.put("label", name);
        m.put("idStrategy", IdStrategy.DISTRIBUTED_LONG);
        return m;
    }

    /** {@code fieldType} as the value supplied — workspace side passes the enum, baseline the code string. */
    private static Map<String, Object> field(long id, String modelName, String fieldName, String columnName,
                                             Object fieldType) {
        Map<String, Object> f = new HashMap<>();
        f.put("id", id);
        f.put("modelName", modelName);
        f.put("fieldName", fieldName);
        f.put("columnName", columnName);
        f.put("fieldType", fieldType);
        return f;
    }

    @Test
    @DisplayName("rename → CHANGE COLUMN (not DROP+ADD); new model → CREATE TABLE")
    void differOutputDrivesRenameAwareDdl() {
        // DESIRED (live workspace): Customer keeps its row but renames code → cust_code; Order is new.
        // Workspace fieldType is the FieldType ENUM (as the live rows carry it).
        DesignRows desired = new DesignRows(
                List.of(model(1, "Customer", "customer"), model(2, "Order", "orders")),
                List.of(field(10, "Customer", "code", "cust_code", FieldType.STRING),
                        field(20, "Order", "amount", "amount", FieldType.STRING)),
                List.of(), List.of(), List.of());

        // OBSERVED (the env's own rows): Customer.code still "code"; fieldType is the CODE STRING
        // ("String") — the shape JSON serialization leaves behind. Identity is the business key alone,
        // so the two rows pair on modelName.fieldName (= "Customer.code", stable across a
        // column-only rename) → an in-place UPDATE on columnName, not a drop+add.
        DesignRows observed = new DesignRows(
                List.of(model(1, "Customer", "customer")),
                List.of(field(10, "Customer", "code", "code", "String")),
                List.of(), List.of(), List.of());

        List<RowChangeDTO> changes = differ.diff(desired, observed);
        String sql = renderer.generateDdlResult(mysql, changes).combinedDdl();

        // Rename is preserved as an in-place column rename — the whole point of the row-keyed diff.
        assertTrue(sql.contains("CHANGE COLUMN code cust_code"), sql);
        // ...and is NOT a destructive drop+add (which would lose the column's data).
        assertFalse(sql.contains("DROP COLUMN code"), sql);
        // The new model becomes a CREATE TABLE for its physical table.
        assertTrue(sql.contains("CREATE TABLE") && sql.contains("orders"), sql);
    }
}
