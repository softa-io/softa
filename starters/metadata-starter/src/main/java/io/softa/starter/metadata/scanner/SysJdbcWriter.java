package io.softa.starter.metadata.scanner;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.jdbc.database.DBUtil;
import io.softa.starter.metadata.catalog.SysCatalog;
import io.softa.starter.metadata.entity.*;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;

/**
 * Pure-JDBC writer that materializes a {@link SchemaDiff} into INSERT /
 * UPDATE / DELETE statements against the 5 annotation-managed {@code sys_*}
 * tables.
 *
 * <p>SQL is generated from {@link SysCatalog} (the entities' own
 * {@code @Model} / {@code @Field}); column order and bind-parameter order are
 * produced together, so they cannot drift apart. Bypasses
 * {@code SysModelService} / {@code ModelManager} (same rationale as
 * {@link SysJdbcLoader}: avoid a circular dependency with the metadata cache
 * the scanner is populating).
 *
 * <p>Rows are matched by business key; a same-key row is updated in place (or
 * renamed via its surrogate {@code id}), so the scanner and any other writer
 * converge on one row per key rather than duplicating it.
 *
 * <p>Per-INSERT structural columns are fixed: {@code app_code} stamped
 * server-side from the configured {@code system.app-code} (wire /
 * entity values are never trusted for identity), and audit columns from
 * {@code now()} / {@code 'scanner'}.
 */
@Slf4j
public final class SysJdbcWriter {

    private static final String ACTOR = "scanner";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String appCode;
    private final String datasourceUrl;

    public SysJdbcWriter(JdbcTemplate jdbcTemplate, String appCode, String datasourceUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.appCode = appCode;
        this.datasourceUrl = datasourceUrl;
        this.transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource()));
    }

    /**
     * Apply the diff to {@code sys_*} within a single transaction. If any
     * INSERT / UPDATE / DELETE fails, all changes are rolled back. DDL
     * execution (in {@code DdlOrchestrator}) is not part of this transaction —
     * the scanner runs it <b>before</b> this commit, so a failed boot in either
     * step leaves the rows unwritten and the next boot retries the same diff.
     *
     * <p>Order — option sets before items, models before fields/indexes — keeps
     * parent rows present before children, matching the original behavior. A
     * declared model rename cascades its new {@code modelName} onto
     * the model's field / index rows <b>between</b> the model and field/index
     * steps, so an unchanged field under a renamed model is re-pointed (not
     * churned) and a same-key field MODIFY then matches on the new name.
     */
    public void apply(SchemaDiff diff) {
        transactionTemplate.executeWithoutResult(status -> {
            applyEntity(SysOptionSet.class, diff.optionSets());
            applyEntity(SysModel.class, diff.models());
            cascadeModelRenames(diff.models());
            applyEntity(SysOptionItem.class, diff.optionItems());
            applyEntity(SysField.class, diff.fields());
            applyEntity(SysModelIndex.class, diff.modelIndexes());
        });
    }

    private <E> void applyEntity(Class<E> type, SchemaDiff.EntityDiff<E> d) {
        if (d.isEmpty()) {
            return;
        }
        SysCatalog.SysTable<E> t = SysCatalog.of(type);
        if (!d.added().isEmpty()) {
            List<Object[]> batch = new ArrayList<>();
            for (E e : d.added()) {
                batch.add(insertArgs(t, e));
            }
            jdbcTemplate.batchUpdate(insertSql(t), batch);
        }
        if (!d.modified().isEmpty()) {
            // Same-key attribute change (MODIFY) and declared rename (RENAME, key
            // changed) need different WHERE clauses: MODIFY matches on the business
            // key, RENAME on the surrogate id (the key is what's changing).
            List<Object[]> mods = new ArrayList<>();
            List<Object[]> renames = new ArrayList<>();
            for (SchemaDiff.Modification<E> mod : d.modified()) {
                if (mod.kind() == SchemaDiff.Kind.RENAME) {
                    renames.add(renameArgs(t, mod.fromCode(), mod.fromDb()));
                    warnSequenceCodeOnFieldRename(mod.fromCode(), mod.fromDb());
                } else {
                    mods.add(updateArgs(t, mod.fromCode()));
                }
            }
            if (!mods.isEmpty()) {
                jdbcTemplate.batchUpdate(updateSql(t), mods);
            }
            if (!renames.isEmpty()) {
                jdbcTemplate.batchUpdate(renameSql(t), renames);
            }
        }
        if (!d.removed().isEmpty()) {
            List<Object[]> batch = new ArrayList<>();
            for (E e : d.removed()) {
                batch.add(keyArgs(t, e));
            }
            jdbcTemplate.batchUpdate(deleteSql(t), batch);
        }
    }

    /**
     * Re-point the field / index rows of a declared-renamed model onto its new
     * {@code modelName}. Runs after the model row is renamed and
     * before the field / index diffs: rows that the diff treated as "unchanged"
     * (re-keyed via the {@code DiffEngine} cascade, so they produced no
     * modification) would otherwise keep the prior model name and divorce from
     * their model.
     */
    private void cascadeModelRenames(SchemaDiff.EntityDiff<SysModel> models) {
        for (SchemaDiff.Modification<SysModel> mod : models.modified()) {
            if (mod.kind() != SchemaDiff.Kind.RENAME) {
                continue;
            }
            String oldName = mod.fromDb().getModelName();
            String newName = mod.fromCode().getModelName();
            for (String childTable : List.of("sys_field", "sys_model_index")) {
                jdbcTemplate.update(
                        "UPDATE " + childTable + " SET model_name = ?"
                                + " WHERE model_name = ?",
                        newName, oldName);
            }
            warnSequenceCodeOnModelRename(oldName, newName);
        }
    }

    // ---- sequence-code rename advisories --------------------------------

    /**
     * A declared rename carries the {@code sys_*} rows, but the auto-fill
     * binding code {@code "<Model>.<field>"} also lives in the per-tenant
     * {@code sys_sequence} rows — business data the scanner must not rewrite.
     * Until the admin runs the surfaced UPDATE, every insert on the renamed
     * field fails with SequenceNotFoundException (fail-closed by design), so
     * the advisory prints the exact converging SQL — same posture as the
     * warn-only DROP hints.
     */
    private void warnSequenceCodeOnFieldRename(Object fromCode, Object fromDb) {
        if (fromCode instanceof SysField newField && fromDb instanceof SysField oldField
                && Boolean.TRUE.equals(newField.getAutoSequence())) {
            log.warn("SysJdbcWriter: autoSequence field {}.{} was renamed from `{}` — sys_sequence rows "
                            + "are tenant data and are NOT auto-updated; converge them with:\n{}",
                    newField.getModelName(), newField.getFieldName(), oldField.getFieldName(),
                    fieldRenameSequenceHint(newField, oldField));
        }
    }

    /**
     * Model-rename twin of {@link #warnSequenceCodeOnFieldRename}. Runs after
     * the child-row cascade (so {@code sys_field.model_name} already carries the
     * new name) and before the field diffs (so {@code field_name} is still the
     * pre-rename one — matching the code currently stored in sys_sequence).
     */
    private void warnSequenceCodeOnModelRename(String oldModelName, String newModelName) {
        List<String> sequenceFields = jdbcTemplate.queryForList(
                "SELECT field_name FROM sys_field WHERE model_name = ? AND auto_sequence = 1",
                String.class, newModelName);
        for (String fieldName : sequenceFields) {
            log.warn("SysJdbcWriter: model {} (renamed from {}) has autoSequence field `{}` — sys_sequence "
                            + "rows are tenant data and are NOT auto-updated; converge them with:\n{}",
                    newModelName, oldModelName, fieldName,
                    modelRenameSequenceHint(oldModelName, newModelName, fieldName));
        }
    }

    /**
     * Old code deliberately uses the NEW model name: when a model rename and a
     * field rename land in the same boot, the model-level hint (old→new model,
     * old field name) runs first, so this hint continues from that state.
     */
    static String fieldRenameSequenceHint(SysField newField, SysField oldField) {
        return sequenceCodeUpdateSql(
                newField.getModelName() + "." + oldField.getFieldName(),
                newField.getModelName() + "." + newField.getFieldName());
    }

    static String modelRenameSequenceHint(String oldModelName, String newModelName, String fieldName) {
        return sequenceCodeUpdateSql(
                oldModelName + "." + fieldName,
                newModelName + "." + fieldName);
    }

    private static String sequenceCodeUpdateSql(String oldCode, String newCode) {
        return "UPDATE sys_sequence SET code = '" + newCode + "' WHERE code = '" + oldCode + "';";
    }

    // ---- SQL + args generated from the descriptor ----------------------

    private static <E> String insertSql(SysCatalog.SysTable<E> t) {
        List<String> cols = new ArrayList<>();
        t.keys().forEach(c -> cols.add(c.column()));
        t.data().forEach(c -> cols.add(c.column()));
        if (t.appCodeColumn() != null) {
            cols.add(t.appCodeColumn().column());
        }
        cols.add("created_time");
        cols.add("created_by");
        cols.add("updated_time");
        cols.add("updated_by");
        String placeholders = cols.stream().map(c -> "?").collect(Collectors.joining(", "));
        return "INSERT INTO " + t.table() + " (" + String.join(", ", cols) + ") VALUES (" + placeholders + ")";
    }

    private <E> Object[] insertArgs(SysCatalog.SysTable<E> t, E e) {
        List<Object> args = new ArrayList<>();
        t.keys().forEach(c -> args.add(c.toDb(e)));
        t.data().forEach(c -> args.add(c.toDb(e)));
        if (t.appCodeColumn() != null) {
            args.add(appCode);              // identity stamped server-side
        }
        LocalDateTime now = LocalDateTime.now();
        args.add(now);
        args.add(ACTOR);
        args.add(now);
        args.add(ACTOR);
        return args.toArray();
    }

    private static <E> String updateSql(SysCatalog.SysTable<E> t) {
        String set = t.data().stream().map(c -> c.column() + " = ?").collect(Collectors.joining(", "));
        String where = t.keys().stream().map(c -> c.column() + " = ?").collect(Collectors.joining(" AND "));
        return "UPDATE " + t.table() + " SET " + set + ", updated_time = ?, updated_by = ?"
                + " WHERE " + where;
    }

    private static <E> Object[] updateArgs(SysCatalog.SysTable<E> t, E fromCode) {
        List<Object> args = new ArrayList<>();
        t.data().forEach(c -> args.add(c.toDb(fromCode)));     // SET (from-code)
        args.add(LocalDateTime.now());
        args.add(ACTOR);
        t.keys().forEach(c -> args.add(c.toDb(fromCode)));     // WHERE (key unchanged across the modification)
        return args.toArray();
    }

    /**
     * UPDATE for a declared rename: the business key itself changes,
     * so the SET writes both the key and data columns and the WHERE matches on
     * the surrogate {@code id} (not the key). The {@code id} is never in the SET,
     * so it is preserved — the row keeps its identity, the data is not divorced.
     */
    private static <E> String renameSql(SysCatalog.SysTable<E> t) {
        List<String> setCols = new ArrayList<>();
        t.keys().forEach(c -> setCols.add(c.column() + " = ?"));
        t.data().forEach(c -> setCols.add(c.column() + " = ?"));
        String set = String.join(", ", setCols);
        return "UPDATE " + t.table() + " SET " + set + ", updated_time = ?, updated_by = ?"
                + " WHERE id = ?";
    }

    private static <E> Object[] renameArgs(
            SysCatalog.SysTable<E> t, E fromCode, E fromDb) {
        List<Object> args = new ArrayList<>();
        t.keys().forEach(c -> args.add(c.toDb(fromCode)));     // SET new key (the rename)
        t.data().forEach(c -> args.add(c.toDb(fromCode)));     // SET new data
        args.add(LocalDateTime.now());
        args.add(ACTOR);
        args.add(t.idColumn().toDb(fromDb));                   // WHERE id = <existing row> (key-independent)
        return args.toArray();
    }

    private static <E> String deleteSql(SysCatalog.SysTable<E> t) {
        String where = t.keys().stream().map(c -> c.column() + " = ?").collect(Collectors.joining(" AND "));
        return "DELETE FROM " + t.table() + " WHERE " + where;
    }

    private static <E> Object[] keyArgs(SysCatalog.SysTable<E> t, E e) {
        return t.keys().stream().map(c -> c.toDb(e)).toArray();
    }

    private static final List<String> APP_CODE_TABLES = List.of(
            "sys_model", "sys_field", "sys_option_set", "sys_option_item", "sys_model_index");

    /**
     * Stamp the configured {@code app_code} onto rows that predate the identity
     * column — pre-V8 rows and rows written before this runtime was assigned its
     * identity. Idempotent: only fills {@code NULL}s.
     *
     * @return number of rows backfilled
     */
    public int backfillAppCode() {
        Integer n = transactionTemplate.execute(status -> {
            int rows = 0;
            for (String table : APP_CODE_TABLES) {
                rows += jdbcTemplate.update(
                        "UPDATE " + table + " SET app_code = ?"
                                + " WHERE app_code IS NULL",
                        appCode);
            }
            return rows;
        });
        return n == null ? 0 : n;
    }

    /**
     * Resolve the surrogate FK columns from their business-code back-links, after the diff
     * is applied: {@code sys_field.model_id} / {@code sys_model_index.model_id} from {@code model_name}
     * and {@code sys_option_item.option_set_id} from {@code option_set_code}. These columns are EXCLUDED
     * from the diff ({@code SysCatalog}), so the writer never inserts/updates them — this UPDATE-join is
     * their sole population path on the annotation lane. Idempotent (a deterministic re-derivation) and
     * self-healing across renames (the parent id is stable; both sides carry the new code). Must run
     * after {@link #backfillAppCode()} so the app-scoped join sees a populated {@code app_code}.
     * Flavor-aware (MySQL / PostgreSQL — see {@link SysReferenceSql}).
     *
     * @return number of rows whose surrogate FK was (re)set across the three tables
     */
    public int populateSurrogateFks() {
        DatabaseType type = DBUtil.parseDatabaseType(datasourceUrl);
        Integer n = transactionTemplate.execute(status -> {
            int rows = 0;
            for (String sql : SysReferenceSql.populateStatements(type)) {
                rows += jdbcTemplate.update(sql, appCode);
            }
            return rows;
        });
        return n == null ? 0 : n;
    }

    // ---- debug --------------------------------------------------------

    /** Per-bucket change counts, for the scanner's boot log. */
    public List<String> changeSummary(SchemaDiff diff) {
        List<String> out = new ArrayList<>();
        out.add("models: +" + diff.models().added().size()
                + " -" + diff.models().removed().size()
                + " ~" + diff.models().modified().size());
        out.add("fields: +" + diff.fields().added().size()
                + " -" + diff.fields().removed().size()
                + " ~" + diff.fields().modified().size());
        out.add("optionSets: +" + diff.optionSets().added().size()
                + " -" + diff.optionSets().removed().size()
                + " ~" + diff.optionSets().modified().size());
        out.add("optionItems: +" + diff.optionItems().added().size()
                + " -" + diff.optionItems().removed().size()
                + " ~" + diff.optionItems().modified().size());
        out.add("modelIndexes: +" + diff.modelIndexes().added().size()
                + " -" + diff.modelIndexes().removed().size()
                + " ~" + diff.modelIndexes().modified().size());
        return out;
    }
}
