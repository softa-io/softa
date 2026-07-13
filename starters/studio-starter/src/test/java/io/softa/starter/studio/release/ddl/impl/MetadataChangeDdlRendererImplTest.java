package io.softa.starter.studio.release.ddl.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.metadata.ddl.DdlDialectFactory;
import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.ddl.dialect.MySqlDdlDialect;
import io.softa.starter.metadata.ddl.dialect.PostgreSqlDdlDialect;
import io.softa.starter.metadata.dto.MetaTable;
import io.softa.starter.studio.release.dto.DesignMetaTables;
import io.softa.starter.studio.release.dto.ModelChangesDTO;
import io.softa.starter.studio.release.dto.RowChangeDTO;
import io.softa.starter.studio.release.dto.RowChangeOp;
import io.softa.starter.studio.release.ddl.TestMetadataResolver;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataChangeDdlRendererImplTest {

    private final MetadataChangeDdlRendererImpl renderer = new MetadataChangeDdlRendererImpl();
    private final DdlDialect mysql = new MySqlDdlDialect(TestMetadataResolver.DDL);
    private final DdlDialect postgres = new PostgreSqlDdlDialect(TestMetadataResolver.DDL);

    @Test
    void rendersModelRenameAndDescription() {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addUpdatedRow(rowChange(
                modelData("Order", "biz_order", "Order", "Business order", false),
                mapOf("tableName", "order_old", "label", "Legacy order", "description", "Legacy description")
        ));

        String sql = renderer.generateDdlResult(mysql, flat(modelChanges)).tableDdl();

        assertTrue(sql.contains("Alter table for model: Order"), sql);
        assertTrue(sql.contains("RENAME TABLE order_old TO biz_order;"), sql);
        assertTrue(sql.contains("ALTER TABLE biz_order"), sql);
        assertTrue(sql.contains("COMMENT 'Business order'"), sql);
    }

    @Test
    void rendersFieldAddDropModifyAndRename() {
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(rowChange(
                fieldData("OrderItem", "buyerName", "buyer_name", FieldType.STRING, 64, null,
                        true, false, "Buyer name", "Verified name", null),
                Map.of()
        ));
        fieldChanges.addDeletedRow(rowChange(
                fieldData("OrderItem", "legacyCode", "legacy_code", FieldType.STRING, 32, null,
                        false, false, "Legacy code", null, null),
                Map.of()
        ));
        fieldChanges.addUpdatedRow(rowChange(
                fieldData("OrderItem", "status", "order_status", FieldType.STRING, 32, null,
                        true, false, "Order status", "Status", null),
                mapOf("columnName", "old_status", "label", "Legacy status", "description", "Legacy description")
        ));
        fieldChanges.addUpdatedRow(rowChange(
                fieldData("OrderItem", "amount", "amount", FieldType.BIG_DECIMAL, 12, 2,
                        false, false, "Amount", "Tax included", "0"),
                mapOf("length", 10, "scale", 0, "defaultValue", null, "description", "Tax excluded")
        ));

        String sql = renderer.generateDdlResult(mysql, flat(fieldChanges)).tableDdl();

        assertTrue(sql.contains("ALTER TABLE order_item"), sql);
        assertTrue(sql.contains("ADD COLUMN buyer_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'Verified name'"), sql);
        assertTrue(sql.contains("DROP COLUMN legacy_code"), sql);
        assertTrue(sql.contains("MODIFY COLUMN amount DECIMAL(12,2) DEFAULT 0 COMMENT 'Tax included'"), sql);
        assertTrue(sql.contains("CHANGE COLUMN old_status order_status VARCHAR(32) NOT NULL DEFAULT '' COMMENT 'Status'"), sql);
    }

    @Test
    void builtinResolverFillsTypeDefaultsWhenFieldLengthIsNull() {
        // Invariant: studio publish to a Softa runtime renders on the BUILTIN annotation
        // resolver — the same one the boot scanner uses — so an undeclared-length field gets the
        // type-default width (STRING -> 64, BIG_DECIMAL -> (32,8)), not a bare VARCHAR/DECIMAL. The
        // default TestMetadataResolver returns empty defaults and cannot exercise this fill, yet it is
        // exactly the defaulting the publish path depends on for byte-equality with the boot scanner.
        DdlDialect builtinMysql = DdlDialectFactory.builtin(DatabaseType.MYSQL);
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(rowChange(
                fieldData("Widget", "title", "title", FieldType.STRING, null, null,
                        false, false, "Title", null, null),
                Map.of()));
        fieldChanges.addCreatedRow(rowChange(
                fieldData("Widget", "price", "price", FieldType.BIG_DECIMAL, null, null,
                        false, false, "Price", null, null),
                Map.of()));

        String sql = renderer.generateDdlResult(builtinMysql, flat(fieldChanges)).tableDdl();

        assertTrue(sql.contains("title VARCHAR(64)"), sql);
        assertTrue(sql.contains("price DECIMAL(32,8)"), sql);
    }

    @Test
    void doesNotTreatLabelChangesAsDdlChanges() {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addUpdatedRow(rowChange(
                modelData("Order", "biz_order", "Order", "Business order", false),
                mapOf("label", "Legacy order")
        ));

        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addUpdatedRow(rowChange(
                fieldData("Order", "status", "status", FieldType.STRING, 32, null,
                        true, false, "Order status", "Status", null),
                mapOf("label", "Legacy status")
        ));

        String sql = renderer.generateDdlResult(mysql, flat(modelChanges, fieldChanges)).tableDdl();

        assertTrue(sql.isBlank(), sql);
    }

    @Test
    void rendersIndexDeleteUpdateAndRename() {
        ModelChangesDTO indexChanges = new ModelChangesDTO("DesignModelIndex");
        indexChanges.addDeletedRow(rowChange(
                indexData("Order", "idx_legacy_code", List.of("legacy_code"), false),
                Map.of()
        ));
        indexChanges.addUpdatedRow(rowChange(
                indexData("Order", "idx_status", List.of("status", "tenant_id"), false),
                mapOf("indexFields", List.of("status"), "uniqueIndex", false)
        ));
        indexChanges.addUpdatedRow(rowChange(
                indexData("Order", "idx_new_name", List.of("code"), true),
                mapOf("indexName", "idx_old_name", "indexFields", List.of("code"), "uniqueIndex", false)
        ));

        String sql = renderer.generateDdlResult(mysql, flat(indexChanges)).indexDdl();

        assertTrue(sql.contains("ALTER TABLE order"), sql);
        assertTrue(sql.contains("DROP INDEX idx_legacy_code"), sql);
        assertTrue(sql.contains("DROP INDEX idx_status"), sql);
        assertTrue(sql.contains("ADD INDEX idx_status (status, tenant_id)"), sql);
        assertTrue(sql.contains("DROP INDEX idx_old_name"), sql);
        assertTrue(sql.contains("ADD UNIQUE INDEX idx_new_name (code)"), sql);
    }

    @Test
    void rendersCreatedModelIndexesOnlyThroughCreateTableDdl() {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addCreatedRow(rowChange(
                modelData("Invoice", "invoice", "Invoice", null, false, IdStrategy.DB_AUTO_ID),
                Map.of()
        ));

        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(rowChange(
                fieldData("Invoice", "id", "id", FieldType.LONG, 32, null,
                        true, false, "ID", null, null),
                Map.of()
        ));
        fieldChanges.addCreatedRow(rowChange(
                fieldData("Invoice", "invoiceNo", "invoice_no", FieldType.STRING, 64, null,
                        true, false, "Invoice number", null, null),
                Map.of()
        ));

        ModelChangesDTO indexChanges = new ModelChangesDTO("DesignModelIndex");
        indexChanges.addCreatedRow(rowChange(
                indexData("Invoice", "uk_invoice_no", List.of("invoice_no"), true),
                Map.of()
        ));

        var ddl = renderer.generateDdlResult(mysql, flat(modelChanges, fieldChanges, indexChanges));
        String tableSql = ddl.tableDdl();
        String indexSql = ddl.indexDdl();
        String combinedSql = ddl.combinedDdl();

        assertTrue(tableSql.contains("-- Create tables:"), tableSql);
        assertTrue(tableSql.contains("Create table for model: Invoice"), tableSql);
        assertTrue(tableSql.contains("CREATE TABLE invoice"), tableSql);
        assertTrue(tableSql.contains("UNIQUE KEY uk_invoice_no (invoice_no)"), tableSql);
        assertTrue(indexSql.isBlank(), indexSql);
        assertFalse(combinedSql.contains("-- Create table indexes:"), combinedSql);
        assertFalse(combinedSql.contains("ADD UNIQUE INDEX uk_invoice_no (invoice_no)"), combinedSql);

        var pgDdl = renderer.generateDdlResult(postgres, flat(modelChanges, fieldChanges, indexChanges));
        assertTrue(pgDdl.tableDdl().contains("CREATE UNIQUE INDEX uk_invoice_no ON invoice (invoice_no);"),
                pgDdl.tableDdl());
        assertTrue(pgDdl.indexDdl().isBlank(), pgDdl.indexDdl());
    }

    @Test
    void usesIdStrategyToControlPrimaryKeyAutoIncrement() {
        String autoIncrementSql = renderer.generateDdlResult(mysql, flat(
                createdModelChanges("OrderAuto", IdStrategy.DB_AUTO_ID),
                createdIdFieldChanges("OrderAuto")
        )).combinedDdl();
        // Column-level AUTO_INCREMENT modifier is still emitted for DB_AUTO_ID.
        assertTrue(autoIncrementSql.contains("AUTO_INCREMENT"), autoIncrementSql);
        // But the hardcoded table-level AUTO_INCREMENT=1 seed is intentionally dropped —
        // it collides with replicated / restored data when a table is recreated.
        assertFalse(autoIncrementSql.contains("AUTO_INCREMENT=1"), autoIncrementSql);

        String distributedSql = renderer.generateDdlResult(mysql, flat(
                createdModelChanges("OrderDistributed", IdStrategy.DISTRIBUTED_LONG),
                createdIdFieldChanges("OrderDistributed")
        )).combinedDdl();
        assertFalse(distributedSql.contains("AUTO_INCREMENT"), distributedSql);
        assertFalse(distributedSql.contains("AUTO_INCREMENT=1"), distributedSql);
    }

    @Test
    void usesSliceIdColumnForTimelinePrimaryKey() {
        String sql = renderer.generateDdlResult(mysql, flat(
                createdTimelineModelChanges("PriceTimeline", IdStrategy.DB_AUTO_ID),
                createdSliceIdFieldChanges("PriceTimeline")
        )).combinedDdl();

        assertTrue(sql.contains("slice_id"), sql);
        assertTrue(sql.contains("PRIMARY KEY (slice_id)"), sql);
        assertTrue(sql.contains("AUTO_INCREMENT"), sql);
        // Table-level seed still intentionally absent (see above).
        assertFalse(sql.contains("AUTO_INCREMENT=1"), sql);
    }

    @Test
    void usesRealIndexRenameWhenDefinitionUnchanged() {
        ModelChangesDTO indexChanges = new ModelChangesDTO("DesignModelIndex");
        indexChanges.addUpdatedRow(rowChange(
                indexData("Order", "idx_order_code_new", List.of("code"), true),
                mapOf("indexName", "idx_order_code_old", "indexFields", List.of("code"), "uniqueIndex", true)
        ));

        String mysqlSql = renderer.generateDdlResult(mysql, flat(indexChanges)).indexDdl();
        String pgSql = renderer.generateDdlResult(postgres, flat(indexChanges)).indexDdl();

        assertTrue(mysqlSql.contains("RENAME INDEX idx_order_code_old TO idx_order_code_new"), mysqlSql);
        assertTrue(pgSql.contains("ALTER INDEX idx_order_code_old RENAME TO idx_order_code_new"), pgSql);
    }

    @Test
    void usesRealColumnRenameForPostgreSQL() {
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addUpdatedRow(rowChange(
                fieldData("OrderItem", "status", "order_status", FieldType.STRING, 32, null,
                        true, false, "Order status", "Status", null),
                mapOf("columnName", "old_status", "label", "Legacy status", "description", "Legacy description")
        ));

        String sql = renderer.generateDdlResult(postgres, flat(fieldChanges)).tableDdl();

        assertTrue(sql.contains("ALTER TABLE order_item RENAME COLUMN old_status TO order_status;"), sql);
        assertFalse(sql.contains("DROP COLUMN old_status"), sql);
    }

    @Test
    void rendersForeignKeyColumnsFromStampedRelatedFieldType() {
        // relatedFieldType (+ length) is stamped onto the row at edit time; render reads it directly.
        ModelChangesDTO modelChanges = createdModelChanges("OrderRef", IdStrategy.DB_AUTO_ID);
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(rowChange(
                fieldData("OrderRef", "id", "id", FieldType.LONG, 32, null, true, false, "ID", null, null),
                Map.of()));
        // id-based FK to a Long-PK model renders BIGINT (relatedFieldType = LONG).
        fieldChanges.addCreatedRow(rowChange(
                referenceFieldData("OrderRef", "customer", "customer", "Customer", null, FieldType.LONG, null),
                Map.of()));
        // id-based FK to a String-PK model mirrors the PK -> VARCHAR(24) (the latent bug fixed).
        fieldChanges.addCreatedRow(rowChange(
                referenceFieldData("OrderRef", "account", "account", "Account", null, FieldType.STRING, 24),
                Map.of()));
        // reference-by-code FK mirrors the referenced business-key column -> VARCHAR(3).
        fieldChanges.addCreatedRow(rowChange(
                referenceFieldData("OrderRef", "currency", "currency", "Currency", "code", FieldType.STRING, 3),
                Map.of()));

        String sql = renderer.generateDdlResult(mysql, flat(modelChanges, fieldChanges)).combinedDdl();

        assertTrue(sql.contains("customer BIGINT"), sql);
        assertTrue(sql.contains("account VARCHAR(24)"), sql);
        assertTrue(sql.contains("currency VARCHAR(3)"), sql);
    }

    @Test
    void rendersStampedTypeOnRenamedForeignKey() {
        // Renaming the COLUMN of a TO_ONE FK routes it into renamedFields, which still render a full
        // column type (MySQL CHANGE COLUMN). The stamped relatedFieldType must drive it, not BIGINT.
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addUpdatedRow(rowChange(
                referenceFieldData("OrderRef", "account", "account_id", "Account", null, FieldType.STRING, 24),
                mapOf("columnName", "account")));

        String sql = renderer.generateDdlResult(mysql, flat(fieldChanges)).tableDdl();

        assertTrue(sql.contains("CHANGE COLUMN account account_id VARCHAR(24)"), sql);
        assertFalse(sql.contains("BIGINT"), sql);
    }

    @Test
    void rendersStampedTypeOnUpdatedForeignKey() {
        // Modifying a TO_ONE FK in place (column unchanged) routes it into updatedFields -> MODIFY
        // COLUMN, which also renders a full type from the stamped relatedFieldType.
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        Map<String, Object> current = referenceFieldData("OrderRef", "account", "account", "Account", null,
                FieldType.STRING, 24);
        current.put("required", true);
        fieldChanges.addUpdatedRow(rowChange(
                current,
                mapOf("required", false)));

        String sql = renderer.generateDdlResult(mysql, flat(fieldChanges)).tableDdl();

        assertTrue(sql.contains("MODIFY COLUMN account VARCHAR(24)"), sql);
        assertFalse(sql.contains("BIGINT"), sql);
    }

    private Map<String, Object> referenceFieldData(String modelName, String fieldName, String columnName,
                                                    String relatedModel, String relatedField,
                                                    FieldType relatedFieldType, Integer length) {
        Map<String, Object> data = fieldData(modelName, fieldName, columnName, FieldType.MANY_TO_ONE, length, null,
                false, false, fieldName, null, null);
        data.put("relatedModel", relatedModel);
        data.put("relatedField", relatedField);
        // The physical type the stamper would have resolved onto the row at edit time.
        data.put("relatedFieldType", relatedFieldType);
        return data;
    }

    /**
     * Flatten the per-table {@link ModelChangesDTO} fixtures into the flat {@link RowChangeDTO}
     * list the renderer takes, stamping each row's {@code op} (from its bucket) + {@code table} (from the
     * dto's design model name). The renderer regroups it back per table for rendering, so this preserves
     * the exact per-table/op structure under test.
     */
    private static List<RowChangeDTO> flat(ModelChangesDTO... dtos) {
        List<RowChangeDTO> out = new ArrayList<>();
        for (ModelChangesDTO dto : dtos) {
            MetaTable table = DesignMetaTables.of(dto.getModelName());
            for (RowChangeDTO r : dto.getCreatedRows()) {
                r.setOp(RowChangeOp.CREATE);
                r.setTable(table);
                out.add(r);
            }
            for (RowChangeDTO r : dto.getUpdatedRows()) {
                r.setOp(RowChangeOp.UPDATE);
                r.setTable(table);
                out.add(r);
            }
            for (RowChangeDTO r : dto.getDeletedRows()) {
                r.setOp(RowChangeOp.DELETE);
                r.setTable(table);
                out.add(r);
            }
        }
        return out;
    }

    /** A row-change fixture; {@code op} + {@code table} are stamped later by {@link #flat}. */
    private RowChangeDTO rowChange(Map<String, Object> fullRow, Map<String, Object> previousValues) {
        RowChangeDTO rowChangeDTO = new RowChangeDTO();
        rowChangeDTO.setFullRow(new HashMap<>(fullRow));
        rowChangeDTO.setPreviousValuesForChangedFields(new HashMap<>(previousValues));
        return rowChangeDTO;
    }

    private Map<String, Object> modelData(String modelName, String tableName, String label,
                                          String description, boolean timeline) {
        return modelData(modelName, tableName, label, description, timeline, null);
    }

    private Map<String, Object> modelData(String modelName, String tableName, String label,
                                          String description, boolean timeline, IdStrategy idStrategy) {
        Map<String, Object> data = new HashMap<>();
        data.put("modelName", modelName);
        data.put("tableName", tableName);
        data.put("label", label);
        data.put("description", description);
        data.put("timeline", timeline);
        data.put("idStrategy", idStrategy);
        return data;
    }

    private Map<String, Object> fieldData(String modelName, String fieldName, String columnName, FieldType fieldType,
                                          Integer length, Integer scale, boolean required, boolean dynamic,
                                          String label, String description, String defaultValue) {
        Map<String, Object> data = new HashMap<>();
        data.put("modelName", modelName);
        data.put("fieldName", fieldName);
        data.put("columnName", columnName);
        data.put("fieldType", fieldType);
        data.put("length", length);
        data.put("scale", scale);
        data.put("required", required);
        data.put("dynamic", dynamic);
        data.put("label", label);
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
                modelData(modelName, modelName.toLowerCase(), modelName, null, false, idStrategy),
                Map.of()
        ));
        return modelChanges;
    }

    private ModelChangesDTO createdIdFieldChanges(String modelName) {
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(rowChange(
                fieldData(modelName, "id", "id", FieldType.LONG, 32, null,
                        true, false, "ID", null, null),
                Map.of()
        ));
        return fieldChanges;
    }

    private ModelChangesDTO createdTimelineModelChanges(String modelName, IdStrategy idStrategy) {
        ModelChangesDTO modelChanges = new ModelChangesDTO("DesignModel");
        modelChanges.addCreatedRow(rowChange(
                modelData(modelName, modelName.toLowerCase(), modelName, null, true, idStrategy),
                Map.of()
        ));
        return modelChanges;
    }

    private ModelChangesDTO createdSliceIdFieldChanges(String modelName) {
        ModelChangesDTO fieldChanges = new ModelChangesDTO("DesignField");
        fieldChanges.addCreatedRow(rowChange(
                fieldData(modelName, "sliceId", "slice_id", FieldType.LONG, 32, null,
                        true, false, "Slice ID", null, null),
                Map.of()
        ));
        return fieldChanges;
    }
}
