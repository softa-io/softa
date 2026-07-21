package io.softa.starter.metadata.scanner;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.StorageType;
import io.softa.starter.metadata.entity.*;
import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;
import io.softa.starter.metadata.scanner.annotation.RenameDeclarations;
import io.softa.starter.metadata.scanner.diff.DiffEngine;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2-backed end-to-end test for the scanner write/read path.
 *
 * <p>Verifies the core contract:
 * <ul>
 *   <li>{@link SysJdbcWriter} INSERTs rows into the 5 {@code sys_*} tables.</li>
 *   <li>{@link SysJdbcLoader} SELECTs them back, mapping them to Sys* entities
 *       with annotation-derived attributes intact.</li>
 *   <li>{@link DiffEngine#diff} returns empty when run a second time against
 *       the same code-side input ({@b idempotency}).</li>
 *   <li>Rows are matched purely by business key: a dropped model is deleted, a
 *       changed attribute is updated in place.</li>
 * </ul>
 *
 * <p>Uses H2 in MySQL compatibility mode so the SQL written for production
 * MySQL is exercised faithfully.
 */
class SysJdbcRoundtripTest {

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SysJdbcLoader loader;
    private SysJdbcWriter writer;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        // Fresh in-memory DB per test class; MySQL mode for compatibility.
        ds.setURL("jdbc:h2:mem:scanner_" + System.nanoTime()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.jdbcTemplate = new JdbcTemplate(ds);
        createSysTables();
        this.loader = new SysJdbcLoader(jdbcTemplate);
        // H2 runs in MySQL mode (see URL); the writer uses the url only for flavor detection.
        this.writer = new SysJdbcWriter(jdbcTemplate, "test-app", "jdbc:mysql://localhost/test");
    }

    // ---- schema ---------------------------------------------------------

    private void createSysTables() {
        jdbcTemplate.execute("""
                CREATE TABLE sys_model (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  app_code VARCHAR(64),
                  label VARCHAR(64),
                  model_name VARCHAR(64),
                  display_name VARCHAR(255),
                  search_name VARCHAR(255),
                  default_order VARCHAR(256),
                  table_name VARCHAR(64),
                  soft_delete TINYINT,
                  soft_delete_field VARCHAR(64),
                  active_control TINYINT,
                  timeline TINYINT,
                  id_strategy VARCHAR(64),
                  storage_type VARCHAR(64),
                  version_lock TINYINT,
                  multi_tenant TINYINT,
                  copyable TINYINT NOT NULL DEFAULT 1,
                  data_source VARCHAR(64),
                  service_name VARCHAR(64),
                  business_key VARCHAR(255),
                  partition_field VARCHAR(64),
                  description VARCHAR(256),
                  created_time DATETIME,
                  created_by VARCHAR(64),
                  created_id BIGINT,
                  updated_time DATETIME,
                  updated_id BIGINT,
                  updated_by VARCHAR(64)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE sys_field (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  app_code VARCHAR(64),
                  label VARCHAR(64),
                  field_name VARCHAR(64),
                  column_name VARCHAR(64),
                  model_name VARCHAR(64),
                  model_id BIGINT,
                  description VARCHAR(256),
                  field_type VARCHAR(64),
                  option_set_code VARCHAR(64),
                  related_model VARCHAR(64),
                  related_field VARCHAR(64),
                  on_delete VARCHAR(32),
                  join_model VARCHAR(64),
                  join_left VARCHAR(64),
                  join_right VARCHAR(64),
                  cascaded_field VARCHAR(256),
                  filters VARCHAR(256),
                  default_value VARCHAR(256),
                  length INT,
                  scale INT,
                  required TINYINT,
                  readonly TINYINT,
                  hidden TINYINT,
                  translatable TINYINT,
                  copyable TINYINT NOT NULL DEFAULT 1,
                  unsearchable TINYINT,
                  computed TINYINT,
                  expression TEXT,
                  dynamic TINYINT,
                  encrypted TINYINT,
                  auto_sequence TINYINT,
                  masking_type VARCHAR(64),
                  widget_type VARCHAR(64),
                  related_field_type VARCHAR(64),
                  created_time DATETIME,
                  created_id BIGINT,
                  created_by VARCHAR(64),
                  updated_time DATETIME,
                  updated_id BIGINT,
                  updated_by VARCHAR(64)
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE sys_option_set (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  app_code VARCHAR(64),
                  label VARCHAR(64),
                  option_set_code VARCHAR(64),
                  description VARCHAR(256),
                  created_time DATETIME,
                  created_id VARCHAR(32),
                  created_by VARCHAR(64),
                  updated_time DATETIME,
                  updated_id VARCHAR(32),
                  updated_by VARCHAR(64),
                  active TINYINT DEFAULT 1
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE sys_option_item (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  app_code VARCHAR(64),
                  option_set_id BIGINT,
                  option_set_code VARCHAR(64),
                  sequence INT,
                  item_code VARCHAR(64),
                  label VARCHAR(64),
                  parent_item_code VARCHAR(64),
                  item_tone VARCHAR(64),
                  item_icon VARCHAR(64),
                  description VARCHAR(256),
                  created_time DATETIME,
                  created_id VARCHAR(32),
                  created_by VARCHAR(64),
                  updated_time DATETIME,
                  updated_id VARCHAR(32),
                  updated_by VARCHAR(64),
                  active TINYINT DEFAULT 1
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE sys_model_index (
                  id BIGINT AUTO_INCREMENT PRIMARY KEY,
                  app_code VARCHAR(64),
                  model_name VARCHAR(64),
                  model_id BIGINT,
                  index_name VARCHAR(60),
                  index_fields VARCHAR(255),
                  unique_index TINYINT,
                  message VARCHAR(256),
                  created_time DATETIME,
                  created_id BIGINT,
                  created_by VARCHAR(64),
                  updated_time DATETIME,
                  updated_id BIGINT,
                  updated_by VARCHAR(64)
                )
                """);

        // Mirror the production unique keys (1.Metadata.ddl.sql).
        // (sys_option_item / sys_model_index carry no unique key in production.)
        jdbcTemplate.execute("CREATE UNIQUE INDEX uniq_modelname ON sys_model (model_name)");
        jdbcTemplate.execute("CREATE UNIQUE INDEX uniq_modelname_fieldname ON sys_field (model_name, field_name)");
        jdbcTemplate.execute("CREATE UNIQUE INDEX unique_sys_option_set_code ON sys_option_set (option_set_code)");
    }

    // ---- fixtures -------------------------------------------------------

    private static SysModel model(String name, String tableName) {
        SysModel m = new SysModel();
        m.setModelName(name);
        m.setLabel(name);
        m.setTableName(tableName);
        m.setIdStrategy(IdStrategy.DB_AUTO_ID);
        m.setStorageType(StorageType.RDBMS);
        m.setSoftDelete(false);
        m.setMultiTenant(false);
        m.setBusinessKey(List.of(name + "Code"));
        return m;
    }

    private static SysField field(String modelName, String fieldName, FieldType type) {
        SysField f = new SysField();
        f.setModelName(modelName);
        f.setFieldName(fieldName);
        f.setColumnName(fieldName);
        f.setLabel(fieldName);
        f.setFieldType(type);
        f.setRequired(false);
        return f;
    }

    private static SysOptionSet optionSet(String code, String label) {
        SysOptionSet os = new SysOptionSet();
        os.setOptionSetCode(code);
        os.setLabel(label);
        return os;
    }

    private static SysOptionItem optionItem(String setCode, String code, String name, int seq) {
        SysOptionItem i = new SysOptionItem();
        i.setOptionSetCode(setCode);
        i.setItemCode(code);
        i.setLabel(name);
        i.setSequence(seq);
        return i;
    }

    private static SysModelIndex index(String modelName, String indexName) {
        SysModelIndex idx = new SysModelIndex();
        idx.setModelName(modelName);
        idx.setIndexName(indexName);
        idx.setIndexFields(List.of("code"));
        idx.setUniqueIndex(true);
        idx.setMessage("A record with this value already exists.");
        return idx;
    }

    // ---- tests ----------------------------------------------------------

    @Test
    void writerInserts_loaderReadsBack_diffIsEmpty() {
        AnnotationScanResult fromCode = new AnnotationScanResult(
                List.of(model("Customer", "biz_customer")),
                List.of(field("Customer", "name", FieldType.STRING)),
                List.of(optionSet("Tier", "Customer Tier")),
                List.of(optionItem("Tier", "g", "Gold", 1)));

        DiffEngine diffEngine = new DiffEngine();

        // First boot: from-db is empty, all-added
        SchemaDiff firstDiff = diffEngine.diff(fromCode, loader.load());
        assertEquals(4, firstDiff.totalCount(), "fresh DB: all rows added");
        writer.apply(firstDiff);

        // Second boot: from-db has what we just wrote → diff is empty
        AnnotationScanResult fromDb = loader.load();
        assertEquals(1, fromDb.models().size());
        assertEquals(1, fromDb.fields().size());
        assertEquals(1, fromDb.optionSets().size());
        assertEquals(1, fromDb.optionItems().size());

        SchemaDiff secondDiff = diffEngine.diff(fromCode, fromDb);
        assertTrue(secondDiff.isEmpty(),
                "idempotency: second boot must produce empty diff, got " + secondDiff.totalCount());
    }

    @Test
    void strictLoadPropagatesReadFailure_lenientDegradesToEmpty() {
        // Simulate an older clone missing one of the five tables.
        jdbcTemplate.execute("DROP TABLE sys_model_index");

        assertThrows(BadSqlGrammarException.class, () -> loader.load(),
                "scanner (write path) must refuse to fabricate an empty baseline");

        AnnotationScanResult lenient = loader.loadLenient();
        assertTrue(lenient.isEmpty(), "checker path degrades to empty");
    }

    @Test
    void modifyingFromCode_producesUpdateOnSecondBoot() {
        SysModel customer = model("Customer", "biz_customer");
        AnnotationScanResult firstBoot = new AnnotationScanResult(
                List.of(customer), List.of(), List.of(), List.of());
        writer.apply(new DiffEngine().diff(firstBoot, loader.load()));

        // Second boot with table_name changed
        SysModel customerV2 = model("Customer", "biz_customer_renamed");
        AnnotationScanResult secondBoot = new AnnotationScanResult(
                List.of(customerV2), List.of(), List.of(), List.of());

        SchemaDiff diff = new DiffEngine().diff(secondBoot, loader.load());
        assertEquals(1, diff.models().modified().size(), "table_name change → modified bucket");
        writer.apply(diff);

        String tableName = jdbcTemplate.queryForObject(
                "SELECT table_name FROM sys_model WHERE model_name = 'Customer'",
                String.class);
        assertEquals("biz_customer_renamed", tableName);
    }

    @Test
    void removingFromCode_deletesRowByBusinessKey() {
        SysModel customer = model("Customer", "biz_customer");
        SysModel product = model("Product", "biz_product");
        AnnotationScanResult firstBoot = new AnnotationScanResult(
                List.of(customer, product), List.of(), List.of(), List.of());
        writer.apply(new DiffEngine().diff(firstBoot, loader.load()));

        // Code drops Product
        AnnotationScanResult secondBoot = new AnnotationScanResult(
                List.of(customer), List.of(), List.of(), List.of());
        SchemaDiff diff = new DiffEngine().diff(secondBoot, loader.load());
        assertEquals(1, diff.models().removed().size());
        writer.apply(diff);

        // The dropped model is deleted; the surviving one is untouched.
        Integer prod = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_model WHERE model_name = 'Product'", Integer.class);
        assertEquals(0, prod, "dropped model is deleted");
        Integer cust = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_model WHERE model_name = 'Customer'", Integer.class);
        assertEquals(1, cust, "surviving model is untouched");
    }

    @Test
    void defaultOrder_roundtripsCorrectly() {
        SysModel customer = model("Customer", "biz_customer");
        customer.setDefaultOrder(Orders.of("name ASC, createdTime DESC"));

        AnnotationScanResult fromCode = new AnnotationScanResult(
                List.of(customer), List.of(), List.of(), List.of());
        writer.apply(new DiffEngine().diff(fromCode, loader.load()));

        // Verify persisted value
        String stored = jdbcTemplate.queryForObject(
                "SELECT default_order FROM sys_model WHERE model_name = 'Customer'",
                String.class);
        assertEquals("name ASC, createdTime DESC", stored);

        // Verify roundtrip idempotency: second diff should be empty
        SchemaDiff secondDiff = new DiffEngine().diff(fromCode, loader.load());
        assertTrue(secondDiff.isEmpty(),
                "defaultOrder roundtrip must be idempotent, got " + secondDiff.totalCount());
    }

    @Test
    void enumColumns_roundtripViaJsonValue() {
        SysModel customer = model("Customer", "biz_customer");
        customer.setIdStrategy(IdStrategy.DISTRIBUTED_LONG);
        customer.setStorageType(StorageType.ES);

        AnnotationScanResult fromCode = new AnnotationScanResult(
                List.of(customer), List.of(), List.of(), List.of());
        writer.apply(new DiffEngine().diff(fromCode, loader.load()));

        // Verify the raw column stores the @JsonValue string (not enum.name())
        String idStrategyValue = jdbcTemplate.queryForObject(
                "SELECT id_strategy FROM sys_model WHERE model_name = 'Customer'",
                String.class);
        assertEquals("DistributedLong", idStrategyValue, "id_strategy stored as @JsonValue");

        // And the loader maps it back to the enum constant
        SysModel reloaded = loader.load().models().get(0);
        assertEquals(IdStrategy.DISTRIBUTED_LONG, reloaded.getIdStrategy());
        assertEquals(StorageType.ES, reloaded.getStorageType());
    }

    @Test
    void renameField_updatesInPlaceByIdPreservingTheRow() {
        // First boot: Customer + field 'code'.
        writer.apply(new DiffEngine().diff(
                new AnnotationScanResult(List.of(model("Customer", "biz_customer")),
                        List.of(field("Customer", "code", FieldType.STRING)), List.of(), List.of()),
                loader.load()));
        Long idBefore = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_field WHERE model_name = 'Customer' AND field_name = 'code'", Long.class);

        // Second boot: field renamed code → partnerCode, declared via RenameDeclarations (fieldOldNames).
        AnnotationScanResult renamedBoot = new AnnotationScanResult(
                List.of(model("Customer", "biz_customer")),
                List.of(field("Customer", "partnerCode", FieldType.STRING)),
                List.of(), List.of(), List.of(),
                new RenameDeclarations(Map.of(), Map.of("Customer.partnerCode", "code")));
        SchemaDiff diff = new DiffEngine().diff(renamedBoot, loader.load());
        assertEquals(SchemaDiff.Kind.RENAME, diff.fields().modified().getFirst().kind(),
                "a declared rename is a RENAME modification, not drop+add");
        writer.apply(diff);

        // The row keeps its identity: WHERE targets the surrogate id, id is never in the SET — so data is
        // carried, not divorced. A regression (WHERE on the changed key) would match 0 rows / orphan the old.
        Long idAfter = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_field WHERE model_name = 'Customer' AND field_name = 'partnerCode'", Long.class);
        assertEquals(idBefore, idAfter, "the row keeps its id across the rename");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_field WHERE field_name = 'code'", Integer.class),
                "the prior name is gone (renamed in place, not left behind)");
    }

    @Test
    void modelRename_cascadesModelNameOntoFieldsAndIndexes() {
        // First boot: Customer + field 'code' + a unique index.
        writer.apply(new DiffEngine().diff(
                new AnnotationScanResult(List.of(model("Customer", "biz_customer")),
                        List.of(field("Customer", "code", FieldType.STRING)), List.of(), List.of(),
                        List.of(index("Customer", "uk_customer_code"))),
                loader.load()));
        Long fieldId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_field WHERE model_name = 'Customer' AND field_name = 'code'", Long.class);
        Long indexId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_model_index WHERE model_name = 'Customer'", Long.class);

        // Second boot: model renamed Customer → CustomerV2 (declared). The child field/index carry the new
        // model name but produce no field/index-level modification — cascadeModelRenames is their sole
        // re-point path.
        AnnotationScanResult renamedBoot = new AnnotationScanResult(
                List.of(model("CustomerV2", "biz_customer")),
                List.of(field("CustomerV2", "code", FieldType.STRING)),
                List.of(), List.of(),
                List.of(index("CustomerV2", "uk_customer_code")),
                new RenameDeclarations(Map.of("CustomerV2", "Customer"), Map.of()));
        SchemaDiff diff = new DiffEngine().diff(renamedBoot, loader.load());
        assertEquals(SchemaDiff.Kind.RENAME, diff.models().modified().getFirst().kind());
        writer.apply(diff);

        // The field & index rows are re-pointed to the new model name, keeping their ids (not orphaned under
        // the prior name — which would then break surrogate-FK resolution).
        assertEquals(fieldId, jdbcTemplate.queryForObject(
                "SELECT id FROM sys_field WHERE model_name = 'CustomerV2' AND field_name = 'code'", Long.class));
        assertEquals(indexId, jdbcTemplate.queryForObject(
                "SELECT id FROM sys_model_index WHERE model_name = 'CustomerV2'", Long.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_field WHERE model_name = 'Customer'", Integer.class),
                "no child left under the prior model name");
    }

    @Test
    void applyRollsBackAllRowsWhenALaterStepFails() {
        // The model INSERT (earlier step) succeeds; then two fields sharing a business key collide on the
        // unique (model_name, field_name) index in the later fields step. apply() is a single transaction,
        // so the model INSERT must roll back too — leaving nothing for the next boot to double-apply.
        SysModel customer = model("Customer", "biz_customer");
        SysField f1 = field("Customer", "dup", FieldType.STRING);
        SysField f2 = field("Customer", "dup", FieldType.STRING);   // same business key → unique violation
        SchemaDiff diff = new SchemaDiff(
                new SchemaDiff.EntityDiff<>(List.of(customer), List.of(), List.of()),   // models: 1 add
                new SchemaDiff.EntityDiff<>(List.of(f1, f2), List.of(), List.of()),     // fields: 2 colliding adds
                SchemaDiff.EntityDiff.empty(),
                SchemaDiff.EntityDiff.empty());

        assertThrows(DataAccessException.class, () -> writer.apply(diff));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_model WHERE model_name = 'Customer'", Integer.class),
                "the earlier model INSERT rolled back with the failed field step");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_field WHERE model_name = 'Customer'", Integer.class),
                "no partial field rows survive");
    }

    @Test
    void backfillAppCode_fillsOnlyNullRows_idempotently() {
        // One row predating the identity column (app_code NULL) + one already owned by another app.
        jdbcTemplate.update("INSERT INTO sys_model (model_name, app_code) VALUES ('NullApp', NULL)");
        jdbcTemplate.update("INSERT INTO sys_model (model_name, app_code) VALUES ('OtherApp', 'other-app')");

        int stamped = writer.backfillAppCode();

        assertEquals(1, stamped, "only the app_code IS NULL row is backfilled");
        assertEquals("test-app", jdbcTemplate.queryForObject(
                "SELECT app_code FROM sys_model WHERE model_name = 'NullApp'", String.class));
        assertEquals("other-app", jdbcTemplate.queryForObject(
                "SELECT app_code FROM sys_model WHERE model_name = 'OtherApp'", String.class),
                "another app's row is never overwritten (the NULL-only guard)");
        assertEquals(0, writer.backfillAppCode(), "idempotent: nothing left to backfill on a second run");
    }
}
