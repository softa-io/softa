package io.softa.starter.studio.release.version;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.version.impl.VersionDdlImpl;
import io.softa.starter.studio.template.TestMetadataResolver;
import io.softa.starter.studio.template.ddl.dialect.DdlDialectRegistry;
import io.softa.starter.studio.template.ddl.dialect.MySqlDdlDialect;
import io.softa.starter.studio.template.ddl.dialect.PostgreSqlDdlDialect;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionDdlImplTest {

    private final VersionDdlImpl versionDdl = new VersionDdlImpl(
            new DdlDialectRegistry(List.of(
                    new MySqlDdlDialect(TestMetadataResolver.INSTANCE),
                    new PostgreSqlDdlDialect(TestMetadataResolver.INSTANCE))));

    @Test
    void generateTableDDLHandlesModelRenameAndDescription() {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addUpdatedRow(rowChange(
                "DesignModel",
                1L,
                modelData("Order", "biz_order", "Order", "Business order", false),
                mapOf("tableName", "order_old", "labelName", "Legacy order", "description", "Legacy description"),
                mapOf("tableName", "biz_order", "labelName", "Order", "description", "Business order")
        ));

        String sql = versionDdl.generateTableDDL(DatabaseType.MYSQL, modelChanges, null);

        assertTrue(sql.contains("Alter table for model: Order"), sql);
        assertTrue(sql.contains("RENAME TABLE order_old TO biz_order;"), sql);
        assertTrue(sql.contains("ALTER TABLE biz_order"), sql);
        assertTrue(sql.contains("COMMENT 'Business order'"), sql);
    }

    @Test
    void generateTableDDLHandlesFieldAddDropModifyAndRename() {
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(rowChange(
                "DesignField",
                11L,
                fieldData("OrderItem", "buyerName", "buyer_name", FieldType.STRING, 64, null,
                        true, false, "Buyer name", "Verified name", null),
                Map.of(),
                Map.of()
        ));
        fieldChanges.addDeletedRow(rowChange(
                "DesignField",
                12L,
                fieldData("OrderItem", "legacyCode", "legacy_code", FieldType.STRING, 32, null,
                        false, false, "Legacy code", null, null),
                Map.of(),
                Map.of()
        ));
        fieldChanges.addUpdatedRow(rowChange(
                "DesignField",
                13L,
                fieldData("OrderItem", "status", "order_status", FieldType.STRING, 32, null,
                        true, false, "Order status", "Status", null),
                mapOf("columnName", "old_status", "labelName", "Legacy status", "description", "Legacy description"),
                mapOf("columnName", "order_status", "labelName", "Order status", "description", "Status")
        ));
        fieldChanges.addUpdatedRow(rowChange(
                "DesignField",
                14L,
                fieldData("OrderItem", "amount", "amount", FieldType.BIG_DECIMAL, 12, 2,
                        false, false, "Amount", "Tax included", "0"),
                mapOf("length", 10, "scale", 0, "defaultValue", null, "description", "Tax excluded"),
                mapOf("length", 12, "scale", 2, "defaultValue", "0", "description", "Tax included")
        ));

        String sql = versionDdl.generateTableDDL(DatabaseType.MYSQL, null, fieldChanges);

        assertTrue(sql.contains("ALTER TABLE order_item"), sql);
        assertTrue(sql.contains("ADD COLUMN buyer_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'Verified name'"), sql);
        assertTrue(sql.contains("DROP COLUMN legacy_code"), sql);
        assertTrue(sql.contains("MODIFY COLUMN amount DECIMAL(12,2) DEFAULT 0 COMMENT 'Tax included'"), sql);
        assertTrue(sql.contains("CHANGE COLUMN old_status order_status VARCHAR(32) NOT NULL DEFAULT '' COMMENT 'Status'"), sql);
    }

    @Test
    void generateTableDDLDoesNotTreatLabelChangesAsDdlChanges() {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addUpdatedRow(rowChange(
                "DesignModel",
                15L,
                modelData("Order", "biz_order", "Order", "Business order", false),
                mapOf("labelName", "Legacy order"),
                mapOf("labelName", "Order")
        ));

        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addUpdatedRow(rowChange(
                "DesignField",
                16L,
                fieldData("Order", "status", "status", FieldType.STRING, 32, null,
                        true, false, "Order status", "Status", null),
                mapOf("labelName", "Legacy status"),
                mapOf("labelName", "Order status")
        ));

        String sql = versionDdl.generateTableDDL(DatabaseType.MYSQL, modelChanges, fieldChanges);

        assertTrue(sql.isBlank(), sql);
    }

    @Test
    void generateIndexDDLHandlesDeleteUpdateAndRename() {
        ModelChangesDTO indexChanges = new ModelChangesDTO("DesignModelIndex");
        indexChanges.addDeletedRow(rowChange(
                "DesignModelIndex",
                21L,
                indexData("Order", "idx_legacy_code", List.of("legacy_code"), false),
                Map.of(),
                Map.of()
        ));
        indexChanges.addUpdatedRow(rowChange(
                "DesignModelIndex",
                22L,
                indexData("Order", "idx_status", List.of("status", "tenant_id"), false),
                mapOf("indexFields", List.of("status"), "uniqueIndex", false),
                mapOf("indexFields", List.of("status", "tenant_id"))
        ));
        indexChanges.addUpdatedRow(rowChange(
                "DesignModelIndex",
                23L,
                indexData("Order", "idx_new_name", List.of("code"), true),
                mapOf("indexName", "idx_old_name", "indexFields", List.of("code"), "uniqueIndex", false),
                mapOf("indexName", "idx_new_name", "indexFields", List.of("code"), "uniqueIndex", true)
        ));

        String sql = versionDdl.generateIndexDDL(DatabaseType.MYSQL, indexChanges);

        assertTrue(sql.contains("ALTER TABLE order"), sql);
        assertTrue(sql.contains("DROP INDEX idx_legacy_code"), sql);
        assertTrue(sql.contains("DROP INDEX idx_status"), sql);
        assertTrue(sql.contains("ADD INDEX idx_status (status, tenant_id)"), sql);
        assertTrue(sql.contains("DROP INDEX idx_old_name"), sql);
        assertTrue(sql.contains("ADD UNIQUE INDEX idx_new_name (code)"), sql);
    }

    @Test
    void generateDDLUsesCreatedModelIndexesForNewTables() {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addCreatedRow(rowChange(
                "DesignModel",
                31L,
                modelData("Invoice", "invoice", "Invoice", null, false, IdStrategy.DB_AUTO_ID),
                Map.of(),
                Map.of()
        ));

        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(rowChange(
                "DesignField",
                32L,
                fieldData("Invoice", "id", "id", FieldType.LONG, 32, null,
                        true, false, "ID", null, null),
                Map.of(),
                Map.of()
        ));
        fieldChanges.addCreatedRow(rowChange(
                "DesignField",
                33L,
                fieldData("Invoice", "invoiceNo", "invoice_no", FieldType.STRING, 64, null,
                        true, false, "Invoice number", null, null),
                Map.of(),
                Map.of()
        ));

        ModelChangesDTO indexChanges = new ModelChangesDTO("DesignModelIndex");
        indexChanges.addCreatedRow(rowChange(
                "DesignModelIndex",
                34L,
                indexData("Invoice", "uk_invoice_no", List.of("invoice_no"), true),
                Map.of(),
                Map.of()
        ));

        String sql = versionDdl.generateDDL(DatabaseType.MYSQL, List.of(modelChanges, fieldChanges, indexChanges));

        assertTrue(sql.contains("-- Create tables:"), sql);
        assertTrue(sql.contains("Create table for model: Invoice"), sql);
        assertTrue(sql.contains("CREATE TABLE invoice"), sql);
        assertTrue(sql.contains("-- Create table indexes:"), sql);
        assertTrue(sql.contains("Table indexes for model: Invoice"), sql);
        assertTrue(sql.contains("ADD UNIQUE INDEX uk_invoice_no (invoice_no)"), sql);
    }

    @Test
    void generateDDLUsesIdStrategyToControlPrimaryKeyAutoIncrement() {
        String autoIncrementSql = versionDdl.generateDDL(DatabaseType.MYSQL, List.of(
                createdModelChanges("OrderAuto", IdStrategy.DB_AUTO_ID),
                createdIdFieldChanges("OrderAuto")
        ));
        assertTrue(autoIncrementSql.contains("AUTO_INCREMENT"), autoIncrementSql);
        assertTrue(autoIncrementSql.contains("AUTO_INCREMENT=1"), autoIncrementSql);

        String distributedSql = versionDdl.generateDDL(DatabaseType.MYSQL, List.of(
                createdModelChanges("OrderDistributed", IdStrategy.DISTRIBUTED_LONG),
                createdIdFieldChanges("OrderDistributed")
        ));
        assertFalse(distributedSql.contains("AUTO_INCREMENT"), distributedSql);
        assertFalse(distributedSql.contains("AUTO_INCREMENT=1"), distributedSql);
    }

    @Test
    void generateDDLUsesSliceIdColumnForTimelinePrimaryKey() {
        String sql = versionDdl.generateDDL(DatabaseType.MYSQL, List.of(
                createdTimelineModelChanges("PriceTimeline", IdStrategy.DB_AUTO_ID),
                createdSliceIdFieldChanges("PriceTimeline")
        ));

        assertTrue(sql.contains("slice_id"), sql);
        assertTrue(sql.contains("PRIMARY KEY (slice_id)"), sql);
        assertTrue(sql.contains("AUTO_INCREMENT"), sql);
    }

    @Test
    void generateIndexDDLUsesRealRenameWhenDefinitionUnchanged() {
        ModelChangesDTO indexChanges = new ModelChangesDTO("DesignModelIndex");
        indexChanges.addUpdatedRow(rowChange(
                "DesignModelIndex",
                24L,
                indexData("Order", "idx_order_code_new", List.of("code"), true),
                mapOf("indexName", "idx_order_code_old", "indexFields", List.of("code"), "uniqueIndex", true),
                mapOf("indexName", "idx_order_code_new", "indexFields", List.of("code"), "uniqueIndex", true)
        ));

        String mysqlSql = versionDdl.generateIndexDDL(DatabaseType.MYSQL, indexChanges);
        String pgSql = versionDdl.generateIndexDDL(DatabaseType.POSTGRESQL, indexChanges);

        assertTrue(mysqlSql.contains("RENAME INDEX idx_order_code_old TO idx_order_code_new"), mysqlSql);
        assertTrue(pgSql.contains("ALTER INDEX idx_order_code_old RENAME TO idx_order_code_new"), pgSql);
    }

    @Test
    void generateTableDDLUsesRealRenameForPostgreSQL() {
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addUpdatedRow(rowChange(
                "DesignField",
                25L,
                fieldData("OrderItem", "status", "order_status", FieldType.STRING, 32, null,
                        true, false, "Order status", "Status", null),
                mapOf("columnName", "old_status", "labelName", "Legacy status", "description", "Legacy description"),
                mapOf("columnName", "order_status", "labelName", "Order status", "description", "Status")
        ));

        String sql = versionDdl.generateTableDDL(DatabaseType.POSTGRESQL, null, fieldChanges);

        assertTrue(sql.contains("ALTER TABLE order_item RENAME COLUMN old_status TO order_status;"), sql);
        assertFalse(sql.contains("DROP COLUMN old_status"), sql);
    }

    private RowChangeDTO rowChange(String model, Long rowId, Map<String, Object> currentData,
                                   Map<String, Object> beforeData, Map<String, Object> afterData) {
        RowChangeDTO rowChangeDTO = new RowChangeDTO(model, rowId);
        rowChangeDTO.setCurrentData(new HashMap<>(currentData));
        rowChangeDTO.setDataBeforeChange(new HashMap<>(beforeData));
        rowChangeDTO.setDataAfterChange(new HashMap<>(afterData));
        return rowChangeDTO;
    }

    private Map<String, Object> modelData(String modelName, String tableName, String labelName,
                                          String description, boolean timeline) {
        return modelData(modelName, tableName, labelName, description, timeline, null);
    }

    private Map<String, Object> modelData(String modelName, String tableName, String labelName,
                                          String description, boolean timeline, IdStrategy idStrategy) {
        Map<String, Object> data = new HashMap<>();
        data.put("modelName", modelName);
        data.put("tableName", tableName);
        data.put("labelName", labelName);
        data.put("description", description);
        data.put("timeline", timeline);
        data.put("idStrategy", idStrategy);
        return data;
    }

    private Map<String, Object> fieldData(String modelName, String fieldName, String columnName, FieldType fieldType,
                                          Integer length, Integer scale, boolean required, boolean dynamic,
                                          String labelName, String description, String defaultValue) {
        Map<String, Object> data = new HashMap<>();
        data.put("modelName", modelName);
        data.put("fieldName", fieldName);
        data.put("columnName", columnName);
        data.put("fieldType", fieldType);
        data.put("length", length);
        data.put("scale", scale);
        data.put("required", required);
        data.put("dynamic", dynamic);
        data.put("labelName", labelName);
        data.put("description", description);
        data.put("defaultValue", defaultValue);
        return data;
    }

    private Map<String, Object> indexData(String modelName, String indexName, List<String> indexFields, boolean uniqueIndex) {
        Map<String, Object> data = new HashMap<>();
        data.put("modelName", modelName);
        data.put("indexName", indexName);
        data.put("indexFields", indexFields);
        data.put("uniqueIndex", uniqueIndex);
        return data;
    }

    private Map<String, Object> mapOf(Object... keyValues) {
        Map<String, Object> data = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            data.put((String) keyValues[i], keyValues[i + 1]);
        }
        return data;
    }

    private ModelChangesDTO createdModelChanges(String modelName, IdStrategy idStrategy) {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addCreatedRow(rowChange(
                "DesignModel",
                100L,
                modelData(modelName, modelName.toLowerCase(), modelName, null, false, idStrategy),
                Map.of(),
                Map.of()
        ));
        return modelChanges;
    }

    private ModelChangesDTO createdIdFieldChanges(String modelName) {
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(rowChange(
                "DesignField",
                101L,
                fieldData(modelName, "id", "id", FieldType.LONG, 32, null,
                        true, false, "ID", null, null),
                Map.of(),
                Map.of()
        ));
        return fieldChanges;
    }

    private ModelChangesDTO createdTimelineModelChanges(String modelName, IdStrategy idStrategy) {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addCreatedRow(rowChange(
                "DesignModel",
                102L,
                modelData(modelName, modelName.toLowerCase(), modelName, null, true, idStrategy),
                Map.of(),
                Map.of()
        ));
        return modelChanges;
    }

    private ModelChangesDTO createdSliceIdFieldChanges(String modelName) {
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(rowChange(
                "DesignField",
                103L,
                fieldData(modelName, "sliceId", "slice_id", FieldType.LONG, 32, null,
                        true, false, "Slice ID", null, null),
                Map.of(),
                Map.of()
        ));
        return fieldChanges;
    }
}
