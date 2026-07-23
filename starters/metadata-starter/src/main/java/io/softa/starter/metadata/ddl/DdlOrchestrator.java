package io.softa.starter.metadata.ddl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.jdbc.database.DBUtil;
import io.softa.starter.metadata.ddl.DdlPolicy.ModelOps;
import io.softa.starter.metadata.ddl.context.ModelDdlCtx;
import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.ddl.spi.DdlMetadataResolver;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.scanner.diff.DiffEngine;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;

/**
 * Applies a {@link SchemaDiff} to the database by rendering and executing the
 * appropriate DDL through the dialect-specific {@link DdlDialect}, gated by
 * {@link DdlPolicy}.
 *
 * <p>DDL auto-execute policy:
 * <ul>
 *   <li>CREATE TABLE / ADD COLUMN / MODIFY COLUMN / CHANGE COLUMN (declared
 *       rename) / RENAME TABLE (declared rename) → execute</li>
 *   <li>DROP TABLE / DROP COLUMN / DROP INDEX → never execute; all warn-only
 *       units are collected into a single consolidated WARN whose body is one
 *       copy-paste SQL block (labels ride along as {@code --} comments)</li>
 *   <li>undeclared {@code tableName}-attribute change → warn-only RENAME hint</li>
 * </ul>
 *
 * <p><b>Granularity</b>: every change renders as its own {@link RenderedDdl} and
 * executes <b>one statement at a time</b> ({@link SqlStatements}). This is a
 * correctness constraint, not a style choice: (a) MySQL Connector/J rejects
 * multi-statement strings without {@code allowMultiQueries=true}; (b) the
 * "already applied" degradation below classifies per statement — batching N
 * changes into one statement (or N statements into one execute) lets a
 * duplicate on the first change silently swallow the remaining N-1, after
 * which the committed {@code sys_*} rows make the diff empty and the loss
 * permanent.
 *
 * <p><b>Renames</b> (the {@code renamedFrom} attribute): when declared, the
 * upstream {@link DiffEngine} pairs the removed-old / added-new split into a single
 * {@code Modification(kind=RENAME)}, which this orchestrator renders as
 * {@code CHANGE COLUMN old new ...} (field, kind {@code DECLARED_COLUMN_RENAME}) or
 * {@code RENAME TABLE old TO new} (model, kind {@code DECLARED_TABLE_RENAME}) and
 * <b>auto-executes</b> — the data is preserved in place. Without a declaration the
 * diff still sees {@code added=[new] + removed=[old]} and processes it as ADD COLUMN
 * (auto) + DROP COLUMN (warn-only) — the old column keeps its data, the new is NULL;
 * to rename safely either declare {@code renamedFrom} or pre-stage an explicit
 * {@code CHANGE COLUMN} migration + matching {@code UPDATE sys_field} rows. See
 * {@code annotation-lane.md} Scenario 10 for the workflow.
 *
 * <p>Idempotency: relies on the {@link SchemaDiff} being accurate. If diff
 * says "field added" but the column already exists, the dialect will fail
 * with SQL error 1060 (Duplicate column) on MySQL — caught and degraded to
 * WARN for that statement only (assumes manual run of equivalent SQL already
 * happened); the remaining statements still execute.
 *
 * <p>Failure handling: non-degradable SQL errors propagate as runtime
 * exceptions, which surface in {@code MetadataAnnotationScanner.initialize()}
 * and fail the {@code AppStartup} sequence (fail-fast while the scanner is
 * active). Because the scanner runs DDL <b>before</b> committing the
 * {@code sys_*} rows, a failed boot leaves the catalog rows unwritten — the
 * next boot recomputes the same diff and retries; DDL that already succeeded
 * on the earlier attempt degrades to WARN via the already-applied
 * classification above.
 */
@Slf4j
public class DdlOrchestrator {

    private final JdbcTemplate jdbcTemplate;
    private final DdlMetadataResolver metadataResolver;
    private final String datasourceUrl;

    public DdlOrchestrator(JdbcTemplate jdbcTemplate,
                           DdlMetadataResolver metadataResolver,
                           @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.metadataResolver = metadataResolver;
        this.datasourceUrl = datasourceUrl;
    }

    /**
     * Apply the diff. Called by {@code MetadataAnnotationScanner.initialize()}
     * <b>before</b> {@code SysJdbcWriter.apply(diff)} writes the {@code sys_*}
     * rows, so that a DDL failure leaves the catalog rows unwritten and the
     * next boot retries the same diff.
     *
     * @param diff           the computed schema diff
     * @param allCodeModels  all from-code {@code SysModel}s — used by
     *                       {@link DdlPolicy} to resolve model attributes (e.g.
     *                       custom {@code tableName}) when a model has field/index
     *                       changes but no model-level diff
     * @param allCodeFields  all from-code {@code SysField}s — used to build a
     *                       complete field→column mapping for index DDL, so that
     *                       indexes referencing pre-existing fields with custom
     *                       {@code columnName} are resolved correctly
     */
    public void apply(SchemaDiff diff, List<SysModel> allCodeModels, List<SysField> allCodeFields) {
        List<RenderedDdl> rendered = render(diff, allCodeModels, allCodeFields);
        int executed = 0;
        int skipped = 0;
        List<RenderedDdl> deferred = new ArrayList<>();
        for (RenderedDdl ddl : rendered) {
            if (!ddl.autoExecute()) {
                deferred.add(ddl);
                continue;
            }
            for (String statement : ddl.statements()) {
                if (executeStatement(ddl.kind(), ddl.label(), statement)) {
                    executed++;
                } else {
                    skipped++;
                }
            }
        }
        warnDeferred(deferred);
        log.info("DdlOrchestrator: executed {} DDL statement(s), skipped {} already applied; "
                        + "{} drop/rename operation(s) deferred to manual SQL",
                executed, skipped, deferred.size());
    }

    /**
     * One consolidated WARN for all warn-only units — the body is a single
     * copy-paste SQL block, each unit's label carried as a {@code --} comment
     * line so the block stays a valid SQL script.
     */
    private void warnDeferred(List<RenderedDdl> deferred) {
        if (deferred.isEmpty()) {
            return;
        }
        String block = deferred.stream()
                .map(ddl -> "-- " + ddl.label() + "\n" + ddl.sql())
                .collect(Collectors.joining("\n\n"));
        log.warn("""
                DdlOrchestrator: {} operation(s) not auto-executed (data-bearing changes, like DROP / RENAME).
                To apply manually:
                {}""", deferred.size(), block.indent(4).stripTrailing());
    }

    /**
     * Render the DDL for a diff <b>without executing anything</b> — the render step behind
     * {@link #apply} (which then executes the auto kinds). Returns units in execution
     * order: table renames first (declared → auto RENAME TABLE, undeclared → warn),
     * then per-model CREATE, per-change ALTERs (column adds / modifies / declared
     * renames, then index adds / rebuilds) and per-model DROP hints (warn).
     */
    private List<RenderedDdl> render(SchemaDiff diff, List<SysModel> allCodeModels, List<SysField> allCodeFields) {
        if (diff.isEmpty()) {
            return List.of();
        }
        Map<String, SysModel> modelsByName = allCodeModels.stream()
                .collect(Collectors.toMap(SysModel::getModelName, Function.identity(), (a, b) -> a));
        // field→column lookup grouped by modelName, for index column resolution
        Map<String, Map<String, String>> fieldToColumnByModel = allCodeFields.stream()
                .filter(f -> f.getFieldName() != null && f.getColumnName() != null)
                .collect(Collectors.groupingBy(SysField::getModelName,
                        Collectors.toMap(SysField::getFieldName, SysField::getColumnName, (a, b) -> a)));
        // TO_ONE FK physical types are resolved at reconciliation time (ReferenceColumnResolver
        // stamps relatedFieldType + length/scale onto sys_field) and read straight from the field
        // ctx here — no cross-model lookup at render.

        DdlDialect dialect = resolveDialect();
        List<DdlPolicy.ModelOps> ops = DdlPolicy.classify(diff, modelsByName);
        List<RenderedDdl> out = new ArrayList<>();
        renderTableRenames(diff, out);
        for (ModelOps op : ops) {
            Map<String, String> modelFieldToColumn =
                    fieldToColumnByModel.getOrDefault(op.model().getModelName(), Map.of());
            switch (op.operation()) {
                case CREATE_TABLE -> renderCreate(dialect, op, out);
                case ALTER_TABLE -> renderAlter(dialect, op, modelFieldToColumn, out);
                case ALTER_TABLE_WITH_DROP_WARNING -> {
                    renderAlter(dialect, op, modelFieldToColumn, out);
                    renderDropColumn(dialect, op, out);
                    renderDropIndex(dialect, op, out);
                }
                case DROP_TABLE_WARNING -> renderDropTable(dialect, op, out);
            }
        }
        return out;
    }

    // ---- per-operation rendering --------------------------------------

    private void renderCreate(DdlDialect dialect, ModelOps op, List<RenderedDdl> out) {
        ModelDdlCtx ctx = SysDdlContextBuilder.forCreate(
                op.model(), op.createFields(), op.createIndexes());
        if (ctx.getCreatedFields().isEmpty()) {
            log.debug("DdlOrchestrator: skipping CREATE TABLE for {} (no stored fields)",
                    op.model().getModelName());
            return;
        }
        String sql = dialect.createTableDDL(ctx).toString();
        out.add(RenderedDdl.of(RenderedDdl.Kind.CREATE_TABLE, "CREATE TABLE " + ctx.getTableName(), sql));
    }

    /**
     * Per-change ALTER rendering: every added / modified / declared-renamed column and
     * every added / rebuilt index becomes its own {@link RenderedDdl} (see the class
     * javadoc on why batching would trade correctness for round-trips). Deleted
     * columns / indexes never render here — they are warn-only hints
     * ({@link #renderDropColumn} / {@link #renderDropIndex}).
     */
    private void renderAlter(DdlDialect dialect, ModelOps op,
                             Map<String, String> modelFieldToColumn, List<RenderedDdl> out) {
        SysModel model = op.model();
        for (SysField field : op.fields().added()) {
            renderFieldChange(dialect,
                    SysDdlContextBuilder.forAlter(model, List.of(field), List.of(), List.of(), List.of()),
                    RenderedDdl.Kind.ALTER_TABLE,
                    "ADD COLUMN " + columnLabel(model, field), out);
        }
        for (SysField field : op.fields().updated()) {
            renderFieldChange(dialect,
                    SysDdlContextBuilder.forAlter(model, List.of(), List.of(field), List.of(), List.of()),
                    RenderedDdl.Kind.ALTER_TABLE,
                    "MODIFY COLUMN " + columnLabel(model, field), out);
        }
        for (DdlPolicy.FieldRename rename : op.fields().renamed()) {
            renderFieldChange(dialect,
                    SysDdlContextBuilder.forAlter(model, List.of(), List.of(), List.of(rename), List.of()),
                    RenderedDdl.Kind.DECLARED_COLUMN_RENAME,
                    "CHANGE COLUMN " + rename.oldColumnName() + " -> "
                            + columnLabel(model, rename.field()), out);
        }
        renderIndexChanges(dialect, op, modelFieldToColumn, out);
    }

    private void renderFieldChange(DdlDialect dialect, ModelDdlCtx ctx,
                                   RenderedDdl.Kind kind, String label, List<RenderedDdl> out) {
        if (!ctx.isHasAlterTableChanges()) {
            return;   // e.g. the single field is not stored
        }
        String sql = dialect.alterTableDDL(ctx).toString().trim();
        if (!sql.isEmpty()) {
            out.add(RenderedDdl.of(kind, label, sql));
        }
    }

    private static String columnLabel(SysModel model, SysField field) {
        String column = field.getColumnName() != null && !field.getColumnName().isBlank()
                ? field.getColumnName()
                : StringTools.toUnderscoreCase(field.getFieldName());
        return column + " ON " + effectiveTableName(model);
    }

    private void renderIndexChanges(DdlDialect dialect, ModelOps op,
                                    Map<String, String> modelFieldToColumn, List<RenderedDdl> out) {
        if (op.indexes().added().isEmpty() && op.indexes().updated().isEmpty()) {
            return;
        }
        // Resolve field→column for index column translation. Start from the
        // complete from-code field→column map for this model (covers pre-existing
        // untouched fields with custom columnName), then overlay with diff buckets
        // (which may have newer values for added/updated fields).
        Map<String, String> fieldToColumn = new HashMap<>(modelFieldToColumn);
        addAllFieldMappings(fieldToColumn, op.fields().added());
        addAllFieldMappings(fieldToColumn, op.fields().updated());

        for (SysModelIndex index : op.indexes().added()) {
            renderIndexChange(dialect,
                    SysDdlContextBuilder.forIndexChanges(op.model(), fieldToColumn,
                            List.of(index), List.of(), List.of()),
                    "ADD INDEX " + index.getIndexName(), out);
        }
        // A definition change rebuilds: DROP INDEX + ADD INDEX, two statements executed
        // and classified separately (a missing index on the DROP half degrades via
        // DdlErrorClassifier.isIndexDropAlreadyApplied and the ADD still runs).
        for (SysModelIndex index : op.indexes().updated()) {
            renderIndexChange(dialect,
                    SysDdlContextBuilder.forIndexChanges(op.model(), fieldToColumn,
                            List.of(), List.of(index), List.of()),
                    "REBUILD INDEX " + index.getIndexName(), out);
        }
    }

    private void renderIndexChange(DdlDialect dialect, ModelDdlCtx ctx, String label,
                                   List<RenderedDdl> out) {
        if (!ctx.isHasIndexChanges()) {
            return;
        }
        String sql = dialect.alterIndexDDL(ctx).toString().trim();
        if (!sql.isEmpty()) {
            out.add(RenderedDdl.of(RenderedDdl.Kind.ALTER_INDEX, label, sql));
        }
    }

    private static void addAllFieldMappings(Map<String, String> target, List<SysField> fields) {
        for (SysField f : fields) {
            if (f.getFieldName() != null && f.getColumnName() != null) {
                target.put(f.getFieldName(), f.getColumnName());
            }
        }
    }

    /**
     * Table renames, two flavours:
     * <ul>
     *   <li><b>Declared</b> ({@code kind == RENAME}, the {@code renamedFrom} attribute on the
     *       model): the intent and the data-preserving target are explicit, so the
     *       {@code RENAME TABLE old TO new} <b>auto-executes</b>.</li>
     *   <li><b>Undeclared</b> ({@code kind == MODIFY}, a bare {@code tableName}-attribute
     *       change): could equally be a silent data divorce, so it stays
     *       <b>warn-only</b> with copy-paste SQL — the same risk class as DROP.
     *       Without surfacing it the change would be fully silent: the catalog points
     *       at the new name while the physical table keeps the old one, and every
     *       runtime query on the model fails.</li>
     * </ul>
     * A declared model rename's fields / indexes were re-keyed by the
     * {@link DiffEngine} cascade, so they show no
     * churn here; the row-side {@code modelName} cascade is done by the writer.
     */
    private void renderTableRenames(SchemaDiff diff, List<RenderedDdl> out) {
        for (SchemaDiff.Modification<SysModel> mod : diff.models().modified()) {
            String oldTable = effectiveTableName(mod.fromDb());
            String newTable = effectiveTableName(mod.fromCode());
            if (oldTable.equals(newTable)) {
                continue;
            }
            // ALTER TABLE ... RENAME TO ... is valid across MySQL and PostgreSQL
            // (a single portable form — MySQL also accepts the RENAME TABLE idiom).
            String sql = "ALTER TABLE " + oldTable + " RENAME TO " + newTable + ";";
            boolean declared = mod.kind() == SchemaDiff.Kind.RENAME;
            out.add(RenderedDdl.of(
                    declared ? RenderedDdl.Kind.DECLARED_TABLE_RENAME : RenderedDdl.Kind.UNDECLARED_TABLE_RENAME,
                    "model " + mod.fromCode().getModelName() + " tableName " + oldTable + " -> " + newTable
                            + (declared ? " (declared renamedFrom)" : ""),
                    sql));
        }
    }

    /** Blank tableName means derived: snake_case(modelName) — compare effective names. */
    private static String effectiveTableName(SysModel model) {
        if (model.getTableName() != null && !model.getTableName().isBlank()) {
            return model.getTableName();
        }
        return StringTools.toUnderscoreCase(model.getModelName());
    }

    private void renderDropTable(DdlDialect dialect, ModelOps op, List<RenderedDdl> out) {
        ModelDdlCtx ctx = SysDdlContextBuilder.forDrop(op.model());
        String hintSql = safeDropSql(dialect, ctx);
        out.add(RenderedDdl.of(RenderedDdl.Kind.DROP_TABLE,
                "model " + op.model().getModelName() + " removed (DROP TABLE)", hintSql));
    }

    private void renderDropIndex(DdlDialect dialect, ModelOps op, List<RenderedDdl> out) {
        if (op.indexes().deleted().isEmpty()) {
            return;
        }
        ModelDdlCtx ctx = SysDdlContextBuilder.forIndexChanges(
                op.model(), Map.of(),
                List.of(), List.of(), op.indexes().deleted());
        String hintSql = dialect.alterIndexDDL(ctx).toString().trim();
        out.add(RenderedDdl.of(RenderedDdl.Kind.DROP_INDEX,
                op.indexes().deleted().size() + " index(es) removed on model " + op.model().getModelName(),
                hintSql));
    }

    private void renderDropColumn(DdlDialect dialect, ModelOps op, List<RenderedDdl> out) {
        if (op.fields().deleted().isEmpty()) {
            return;
        }
        // Build a "drop-only" context and render via alterTableDDL; the
        // resulting SQL contains the DROP COLUMN block.
        ModelDdlCtx ctx = SysDdlContextBuilder.forAlter(
                op.model(), List.of(), List.of(), List.of(), op.fields().deleted());
        if (!ctx.isHasAlterTableChanges()) {
            return;
        }
        String hintSql = dialect.alterTableDDL(ctx).toString().trim();
        out.add(RenderedDdl.of(RenderedDdl.Kind.DROP_COLUMN,
                op.fields().deleted().size() + " column(s) removed on model " + op.model().getModelName(),
                hintSql));
    }

    private String safeDropSql(DdlDialect dialect, ModelDdlCtx ctx) {
        try {
            return dialect.dropTableDDL(ctx).toString().trim();
        } catch (RuntimeException e) {
            return "DROP TABLE " + ctx.getTableName() + ";  -- (template render failed: " + e.getMessage() + ")";
        }
    }

    // ---- execute + classify failures ----------------------------------

    /**
     * Execute one statement. Returns {@code true} when executed, {@code false}
     * when skipped as already applied; a genuine failure logs the statement and
     * rethrows (fail-fast, rows stay unwritten).
     */
    private boolean executeStatement(RenderedDdl.Kind kind, String label, String statement) {
        try {
            jdbcTemplate.execute(statement);
            log.info("DdlOrchestrator: {} OK", label);
            return true;
        } catch (BadSqlGrammarException e) {
            if (isAlreadyApplied(kind, e)) {
                log.warn("DdlOrchestrator: {} — statement skipped (already applied: {})", label,
                        DdlErrorClassifier.rootMessage(e));
                return false;
            }
            log.error("DdlOrchestrator: {} FAILED. Statement was:\n{}", label, statement);
            throw e;
        } catch (DataAccessException e) {
            log.error("DdlOrchestrator: {} FAILED. Statement was:\n{}", label, statement);
            throw e;
        }
    }

    /**
     * "Already applied" = the common idempotent-duplicate set, plus the narrow
     * source-already-gone state for the kinds that legitimately re-run against a
     * renamed / rebuilt schema: a {@code CHANGE COLUMN} whose old column is gone
     * ({@code DECLARED_COLUMN_RENAME}), a {@code RENAME TABLE} whose old table is
     * gone ({@code DECLARED_TABLE_RENAME}), and the DROP half of an index rebuild
     * whose index is gone ({@code ALTER_INDEX}). Scoping by kind keeps a genuine
     * unknown-column / missing-table error on an ordinary ALTER surfacing as a
     * hard failure.
     */
    private static boolean isAlreadyApplied(RenderedDdl.Kind kind, BadSqlGrammarException e) {
        if (DdlErrorClassifier.isIdempotentDuplicate(e)) {
            return true;
        }
        return switch (kind) {
            case DECLARED_COLUMN_RENAME -> DdlErrorClassifier.isColumnRenameAlreadyApplied(e);
            case DECLARED_TABLE_RENAME -> DdlErrorClassifier.isTableRenameAlreadyApplied(e);
            case ALTER_INDEX -> DdlErrorClassifier.isIndexDropAlreadyApplied(e);
            default -> false;
        };
    }

    // ---- dialect ------------------------------------------------------

    private DdlDialect resolveDialect() {
        DatabaseType type = DBUtil.parseDatabaseType(datasourceUrl);
        return DdlDialectFactory.create(type, metadataResolver);
    }
}
