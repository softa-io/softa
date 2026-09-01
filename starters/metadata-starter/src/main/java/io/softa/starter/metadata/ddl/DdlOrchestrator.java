package io.softa.starter.metadata.ddl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.softa.framework.base.utils.StringTools;
import io.softa.framework.orm.enums.DatabaseType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.StorageType;
import io.softa.framework.orm.jdbc.database.DBUtil;
import io.softa.starter.metadata.ddl.DdlPolicy.ModelOps;
import io.softa.starter.metadata.ddl.context.IndexDdlCtx;
import io.softa.starter.metadata.ddl.context.ModelDdlCtx;
import io.softa.starter.metadata.ddl.dialect.DdlDialect;
import io.softa.starter.metadata.ddl.introspect.IndexNameCompat;
import io.softa.starter.metadata.ddl.introspect.PhysicalSchema;
import io.softa.starter.metadata.ddl.introspect.PhysicalSchemaReader;
import io.softa.starter.metadata.ddl.introspect.PhysicalTypeCompat;
import io.softa.starter.metadata.ddl.spi.DdlMetadataResolver;
import io.softa.starter.metadata.entity.SysField;
import io.softa.starter.metadata.entity.SysModel;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.scanner.diff.DiffEngine;
import io.softa.starter.metadata.scanner.diff.SchemaDiff;

/**
 * Renders and executes the scanner lane's DDL through the dialect-specific
 * {@link DdlDialect}. Two planners share one execution/classification core:
 *
 * <p><b>Convergence lane ({@link #converge})</b> — the primary path wherever
 * {@code scanner-scope} is active (non-production). Annotations are the single source of
 * truth: the physical schema of every in-scope owned table is converged to the declared
 * shape on every boot, so drift cannot outlive a restart. The verb comes from comparing the
 * from-code definition against the introspected {@link PhysicalSchema} facts; the
 * {@link SchemaDiff} (code vs {@code sys_*} rows) contributes only what the physical shape
 * cannot express — declared rename pairings with their exact prior column names, attribute
 * modifications invisible to introspection (NOT NULL / DEFAULT / COMMENT), and index
 * definition changes behind an unchanged name:
 * <ul>
 *   <li>declared table missing → {@code CREATE TABLE} from the full code definition
 *       (genesis); a pre-existing table is adopted column-by-column instead;</li>
 *   <li>declared table missing but the declared-rename prior table physically present →
 *       {@code RENAME TABLE} (data carried); an <b>undeclared</b> {@code tableName} change
 *       whose old table still physically exists fails the boot — silently creating the new
 *       table would divorce the data, and the planner never guesses;</li>
 *   <li>declared column missing → {@code ADD COLUMN}; with a rename pairing whose prior
 *       column physically exists → {@code CHANGE COLUMN} (both sides physically present
 *       fails fast — a half-applied rename a human must resolve);</li>
 *   <li>declared column present with a DDL-relevant attribute delta in the diff, or a
 *       physical type/width mismatch ({@link PhysicalTypeCompat} — the same comparator the
 *       drift audit uses, so audit and execution can never disagree) → {@code MODIFY} to the
 *       declared shape. Narrowing and type-family changes execute here: the declaration is
 *       the truth and the environment is by definition non-production;</li>
 *   <li>undeclared physical columns / indexes on an owned table → {@code DROP} — the
 *       destructive half of "eliminate drift". Projections are never planned (their table
 *       belongs to the owner), and whole undeclared <i>tables</i> stay untouched: nothing
 *       proves their ownership (another app_code, a legacy table), so they remain a
 *       report-only concern of the drift audit;</li>
 *   <li>declared index missing → {@code ADD INDEX}; definition change → rebuild
 *       (DROP + ADD). Index-name matching goes through {@link IndexNameCompat} so
 *       engine-mangled names (H2 unique-index suffixes) neither flap nor get dropped.</li>
 * </ul>
 *
 * <p><b>Metadata-only lane ({@link #apply})</b> — the fallback when physical introspection
 * is unavailable: plans purely from the {@link SchemaDiff} with the conservative
 * pre-convergence policy (additive auto; DROP / undeclared-rename / anything destructive
 * warn-only with copy-paste SQL), because without facts the planner cannot distinguish
 * drift from intent.
 *
 * <p>{@link #reconcilePhysical} is the additive-only convergence over the {@code sys_*}
 * catalog tables themselves (boot Stage A, before the strict catalog read): same planner,
 * destructive verbs disabled — it runs before the diff exists and must be safe under narrow
 * scopes where the catalog is somebody else's to manage.
 *
 * <p><b>Granularity</b>: every change renders as its own {@link RenderedDdl} and executes
 * <b>one statement at a time</b> ({@link SqlStatements}). This is a correctness constraint,
 * not a style choice: (a) MySQL Connector/J rejects multi-statement strings without
 * {@code allowMultiQueries=true}; (b) the "already applied" degradation classifies per
 * statement — batching N changes into one statement lets a duplicate on the first change
 * silently swallow the remaining N-1, after which the committed {@code sys_*} rows make the
 * diff empty and the loss permanent.
 *
 * <p><b>Renames</b> (the {@code renamedFrom} attribute): when declared, the upstream
 * {@link DiffEngine} pairs the removed-old / added-new split into a single
 * {@code Modification(kind=RENAME)} carrying the exact prior column / table name from the
 * db row. The convergence lane additionally honors the attribute straight from the code
 * definition (prior name via {@code snake_case}) so a rename whose rows were already
 * updated still heals a lagging physical schema instead of divorcing it into ADD + DROP.
 *
 * <p>Failure handling: non-degradable SQL errors propagate as runtime exceptions, which
 * surface in {@code MetadataAnnotationScanner.initialize()} and fail the {@code AppStartup}
 * sequence (fail-fast while the scanner is active). Because the scanner runs DDL
 * <b>before</b> committing the {@code sys_*} rows, a failed boot leaves the catalog rows
 * unwritten — the next boot recomputes the same plan and retries; DDL that already
 * succeeded degrades to WARN via the already-applied classification
 * ({@link DdlErrorClassifier}).
 */
@Slf4j
public class DdlOrchestrator {

    /** Label marker on units planned by {@link #reconcilePhysical} (the catalog-table boot path). */
    static final String CATALOG_TAG = "[catalog]";

    /** Label marker on convergence units that exist purely because the physical schema drifted. */
    static final String CONVERGE_TAG = "[converge]";

    private final JdbcTemplate jdbcTemplate;
    private final DdlMetadataResolver metadataResolver;
    private final String datasourceUrl;

    /** Probed lazily and at most once: whether PostgreSQL can build a trigram index right now. */
    private TrigramCapability trigramCapability;

    public DdlOrchestrator(JdbcTemplate jdbcTemplate,
                           DdlMetadataResolver metadataResolver,
                           @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.jdbcTemplate = jdbcTemplate;
        this.metadataResolver = metadataResolver;
        this.datasourceUrl = datasourceUrl;
    }

    // ---- metadata-only lane (no physical facts) ------------------------

    /**
     * Apply the diff planning purely from the metadata delta — the fallback lane for a boot
     * whose physical introspection failed ({@link #introspect} returned {@code null}).
     * Conservative policy: additive changes and declared renames auto-execute; DROPs,
     * undeclared table renames and everything else destructive defer to one consolidated
     * warn-only SQL block, because without facts drift and intent are indistinguishable.
     *
     * <p>Called <b>before</b> {@code SysJdbcWriter.apply(diff)} writes the {@code sys_*}
     * rows, so a DDL failure leaves the catalog rows unwritten and the next boot retries.
     */
    public void apply(SchemaDiff diff, List<SysModel> allCodeModels, List<SysField> allCodeFields) {
        List<RenderedDdl> rendered = render(diff, allCodeModels, allCodeFields);
        ExecResult result = executeAll(rendered, false);
        warnDeferred(result.deferred());
        log.info("DdlOrchestrator: executed {} DDL statement(s), skipped {} already applied; "
                        + "{} drop/rename operation(s) deferred to manual SQL",
                result.executed(), result.skipped(), result.deferred().size());
    }

    /** Outcome of one execution pass over rendered units. */
    private record ExecResult(int executed, int skipped, List<RenderedDdl> deferred) {
    }

    /**
     * Execute rendered units statement by statement, deferring the warn-only kinds unless
     * {@code executeEverything} (the convergence lane, where destructive verbs are policy).
     */
    private ExecResult executeAll(List<RenderedDdl> rendered, boolean executeEverything) {
        int executed = 0;
        int skipped = 0;
        List<RenderedDdl> deferred = new ArrayList<>();
        for (RenderedDdl ddl : rendered) {
            if (!executeEverything && !ddl.autoExecute()) {
                deferred.add(ddl);
                continue;
            }
            boolean firstStatement = true;
            for (String statement : ddl.statements()) {
                boolean ran = executeStatement(ddl.kind(), ddl.label(), statement);
                if (ran) {
                    executed++;
                } else {
                    skipped++;
                }
                // A degraded CREATE TABLE means the table pre-exists with an unknown shape: the
                // unit's remaining statements (PostgreSQL renders COMMENT ON ... separately) may
                // reference columns the physical table lacks — skip them. Index-rebuild
                // units keep running after a degraded DROP half (the ADD half must still apply).
                if (firstStatement && !ran && ddl.kind() == RenderedDdl.Kind.CREATE_TABLE) {
                    break;
                }
                firstStatement = false;
            }
        }
        return new ExecResult(executed, skipped, deferred);
    }

    /**
     * Snapshot the managed tables' physical shape for convergence planning — also the
     * drift audit's fact source, so one snapshot serves both. Any failure (introspection
     * is an optimization, never a gate) degrades to "no facts" — the caller then falls
     * back to the metadata-only lane.
     */
    public PhysicalSchema introspect(List<SysModel> allCodeModels) {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            return null;
        }
        try {
            return PhysicalSchemaReader.readManagedTables(dataSource, allCodeModels);
        } catch (Exception e) {
            log.warn("DdlOrchestrator: physical schema introspection unavailable — convergence "
                    + "planning disabled for this run: {}", e.getMessage());
            return null;
        }
    }

    // ---- convergence lane (annotations vs physical facts) ---------------

    /**
     * Converge every in-scope model's physical table to its from-code definition — the
     * scanner's primary DDL path (see the class javadoc for the verb table). The
     * {@code diff} supplies the deltas introspection cannot see; everything else derives
     * from {@code facts}. Executes all planned units, destructive ones included.
     *
     * @return whether any statement was actually executed (callers refresh their snapshot
     *         only then)
     */
    public boolean converge(List<SysModel> codeModels, List<SysField> codeFields,
                            List<SysModelIndex> codeIndexes, SchemaDiff diff, PhysicalSchema facts) {
        DdlDialect dialect = resolveDialect();
        Map<String, SysModel> modelsByName = codeModels.stream()
                .collect(Collectors.toMap(SysModel::getModelName, Function.identity(), (a, b) -> a));
        Map<String, ModelOps> diffHints = DdlPolicy.classify(diff, modelsByName).stream()
                .collect(Collectors.toMap(op -> op.model().getModelName(), Function.identity(), (a, b) -> a));
        Map<String, SchemaDiff.Modification<SysModel>> modelMods = diff.models().modified().stream()
                .collect(Collectors.toMap(m -> m.fromCode().getModelName(), Function.identity(), (a, b) -> a));
        Map<String, List<SysField>> fieldsByModel = codeFields.stream()
                .collect(Collectors.groupingBy(SysField::getModelName));
        Map<String, List<SysModelIndex>> indexesByModel = codeIndexes.stream()
                .collect(Collectors.groupingBy(SysModelIndex::getModelName));

        List<RenderedDdl> out = new ArrayList<>();
        for (SysModel model : codeModels) {
            if (Boolean.TRUE.equals(model.getProjection())) {
                continue;   // a projection owns no table — its shape belongs to the owner
            }
            if (model.getStorageType() != null && model.getStorageType() != StorageType.RDBMS) {
                continue;   // no physical table to converge on this storage axis
            }
            ModelOps hints = diffHints.get(model.getModelName());
            planConvergence(dialect, model,
                    fieldsByModel.getOrDefault(model.getModelName(), List.of()),
                    indexesByModel.getOrDefault(model.getModelName(), List.of()),
                    hints == null ? DdlPolicy.FieldOps.EMPTY : hints.fields(),
                    hints == null ? DdlPolicy.IndexOps.EMPTY : hints.indexes(),
                    modelMods.get(model.getModelName()),
                    facts, true, CONVERGE_TAG, out);
        }
        if (out.isEmpty()) {
            log.info("DdlOrchestrator: physical schema of {} in-scope model(s) already matches "
                    + "the annotations", codeModels.size());
            return false;
        }
        ExecResult result = executeAll(out, true);
        log.info("DdlOrchestrator: converged {} in-scope model(s) — executed {} DDL statement(s), "
                        + "skipped {} already applied",
                codeModels.size(), result.executed(), result.skipped());
        return result.executed() > 0;
    }

    /**
     * Additive-only convergence of the given models' physical tables <b>directly from their
     * from-code definitions</b> — the boot path for the {@code sys_*} catalog tables
     * themselves, whose "last applied state" is recorded nowhere but the physical schema
     * (the rows that record every other model's state live <i>inside</i> these tables). It
     * runs <b>before</b> {@code SysJdbcLoader}'s strict read, so after it returns the read
     * set is structurally guaranteed to be ⊆ the physical column set.
     *
     * <p>Destructive verbs are disabled here: this stage runs with no diff and possibly a
     * narrow {@code scanner-scope} that does not manage the catalog, so it may only grow
     * the schema (CREATE / ADD / declared-rename CHANGE / widening MODIFY / ADD INDEX);
     * narrowing, type-family changes and undeclared extras stay with the drift audit — or
     * with the full convergence pass when the catalog packages are in scope.
     *
     * @return whether any statement was actually executed (callers refresh their
     *         snapshot only then)
     */
    public boolean reconcilePhysical(List<SysModel> models, List<SysField> fields,
                                     List<SysModelIndex> indexes, PhysicalSchema facts) {
        DdlDialect dialect = resolveDialect();
        List<RenderedDdl> out = new ArrayList<>();
        for (SysModel model : models) {
            List<SysField> modelFields = fields.stream()
                    .filter(f -> model.getModelName().equals(f.getModelName())).toList();
            List<SysModelIndex> modelIndexes = indexes.stream()
                    .filter(i -> model.getModelName().equals(i.getModelName())).toList();
            planConvergence(dialect, model, modelFields, modelIndexes,
                    DdlPolicy.FieldOps.EMPTY, DdlPolicy.IndexOps.EMPTY, null,
                    facts, false, CATALOG_TAG, out);
        }
        if (out.isEmpty()) {
            log.info("DdlOrchestrator: {} {} table(s) physically in sync", CATALOG_TAG, models.size());
            return false;
        }
        ExecResult result = executeAll(out, false);
        warnDeferred(result.deferred());
        log.info("DdlOrchestrator: {} reconciled {} table(s) — executed {} DDL statement(s), "
                        + "skipped {} already applied",
                CATALOG_TAG, models.size(), result.executed(), result.skipped());
        return result.executed() > 0;
    }

    /**
     * Plan one model's convergence against the facts. {@code destructive} distinguishes the
     * full lane (narrowing MODIFY, undeclared DROPs) from the additive Stage-A lane; the
     * diff buckets and {@code modelMod} are empty / {@code null} on the additive lane.
     *
     * <p>Package-private for the plan-order test: the undeclared-index DROP must precede the
     * undeclared-column DROP (a column drop while a composite unique index still holds the
     * column makes MySQL shrink the key and fail on valid data), and only the rendered plan
     * can pin that — H2 handles the same column drop differently, so an execution-level test
     * cannot see the hazard.
     */
    void planConvergence(DdlDialect dialect, SysModel model,
                                 List<SysField> modelFields, List<SysModelIndex> modelIndexes,
                                 DdlPolicy.FieldOps diffFields, DdlPolicy.IndexOps diffIndexes,
                                 SchemaDiff.Modification<SysModel> modelMod,
                                 PhysicalSchema facts, boolean destructive, String tag,
                                 List<RenderedDdl> out) {
        String table = effectiveTableName(model);
        // The facts may still know the table under its pre-rename name; lookups then go
        // against that name while rendered ALTERs use the new one (the RENAME unit runs first).
        String factsTable = planTableIdentity(model, modelMod, facts, tag, out);
        if (factsTable == null) {
            // Physically absent under both names: genesis CREATE from the full code
            // definition (columns + indexes in one unit) — no per-column work.
            planGenesis(dialect, model, modelFields, modelIndexes, tag, out);
            return;
        }

        Map<String, String> diffRenames = new HashMap<>();
        for (DdlPolicy.FieldRename rename : diffFields.renamed()) {
            diffRenames.put(rename.field().getFieldName(), rename.oldColumnName());
        }
        Map<String, SysField> diffModified = diffFields.updated().stream()
                .collect(Collectors.toMap(SysField::getFieldName, Function.identity(), (a, b) -> a));

        Set<String> declaredColumns = new HashSet<>();
        Set<String> renameSourceColumns = new HashSet<>();
        for (SysField field : modelFields) {
            if (!SysDdlContextBuilder.isStored(field)) {
                continue;
            }
            String column = effectiveColumnName(field);
            declaredColumns.add(lower(column));
            // The diff pairing carries the exact prior column (custom columnName included);
            // the code attribute is the fallback for drift healing after the rows already
            // moved on — there the prior column is its snake_case derivation.
            String oldColumn = diffRenames.get(field.getFieldName());
            if (oldColumn == null && StringUtils.isNotBlank(field.getRenamedFrom())) {
                oldColumn = StringTools.toUnderscoreCase(field.getRenamedFrom());
            }
            boolean columnExists = facts.columnExists(factsTable, column);
            boolean oldExists = oldColumn != null && !lower(oldColumn).equals(lower(column))
                    && facts.columnExists(factsTable, oldColumn);
            if (oldExists) {
                renameSourceColumns.add(lower(oldColumn));
            }
            if (columnExists && oldExists) {
                throw new IllegalStateException(String.format(
                        "Half-applied column rename on %s: both '%s' (declared renamedFrom) and "
                                + "'%s' physically exist. Resolve manually — carry the data into '%s', then "
                                + "DROP COLUMN %s — and boot again.",
                        factsTable, oldColumn, column, column, oldColumn));
            }
            if (!columnExists) {
                if (oldExists) {
                    addIfRendered(out, renderedFieldChange(dialect,
                            SysDdlContextBuilder.forAlter(model, List.of(), List.of(),
                                    List.of(new DdlPolicy.FieldRename(field, oldColumn)), List.of()),
                            RenderedDdl.Kind.DECLARED_COLUMN_RENAME,
                            "CHANGE COLUMN " + oldColumn + " -> " + columnLabel(model, field)
                                    + " " + tag + " declared renamedFrom"));
                } else {
                    addIfRendered(out, renderedFieldChange(dialect,
                            SysDdlContextBuilder.forAlter(model, List.of(field), List.of(), List.of(), List.of()),
                            RenderedDdl.Kind.ALTER_TABLE,
                            "ADD COLUMN " + columnLabel(model, field) + " " + tag));
                }
                continue;
            }
            if (diffModified.containsKey(field.getFieldName())) {
                // The declaration itself changed (type, width, NOT NULL, DEFAULT, COMMENT) —
                // re-state the full declared shape. This also covers a physical mismatch on
                // the same column, so no drift unit is planned on top of it.
                addIfRendered(out, renderedFieldChange(dialect,
                        SysDdlContextBuilder.forAlter(model, List.of(), List.of(field), List.of(), List.of()),
                        RenderedDdl.Kind.ALTER_TABLE,
                        "MODIFY COLUMN " + columnLabel(model, field)));
                continue;
            }
            PhysicalSchema.PhysicalColumn observed = facts.column(factsTable, column);
            PhysicalTypeCompat.Verdict verdict = PhysicalTypeCompat.compare(field, observed);
            if (verdict == PhysicalTypeCompat.Verdict.EQUAL) {
                continue;
            }
            // WIDEN triggers only on a real declared width bound: a declared-unbounded column
            // (TEXT/JSON/DTO) is excluded — engines report its width inconsistently
            // (H2-MySQL: VARCHAR(1e9)), so a width-based trigger there would re-plan the same
            // MODIFY on every boot.
            if (verdict == PhysicalTypeCompat.Verdict.WIDEN && !isBoundedDeclaredWidth(field)) {
                continue;
            }
            if (!destructive && verdict != PhysicalTypeCompat.Verdict.WIDEN) {
                continue;   // additive lane: narrowing / type-family drift stays with the audit
            }
            addIfRendered(out, renderedFieldChange(dialect,
                    SysDdlContextBuilder.forAlter(model, List.of(), List.of(field), List.of(), List.of()),
                    RenderedDdl.Kind.ALTER_TABLE,
                    "MODIFY COLUMN " + columnLabel(model, field) + " " + tag + " "
                            + verdict.name().toLowerCase(Locale.ROOT) + ": "
                            + PhysicalTypeCompat.describe(field, observed)));
        }

        PhysicalSchema.PhysicalTable physical = facts.tables().get(lower(factsTable));

        planIndexConvergence(dialect, model, modelFields, modelIndexes, diffIndexes,
                physical, destructive, tag, out);

        // Undeclared-column drops MUST come after the index convergence above: dropping a
        // column that still participates in a (soon-to-be-dropped) composite unique index
        // makes MySQL silently shrink the index to its remaining columns first — and the
        // shrunken key then fails with a duplicate-entry error on perfectly valid data
        // (seen live: DROP COLUMN code while uk_..._tenant_code(tenant_id, code) still
        // existed collapsed the key to (tenant_id)). With the undeclared DROP INDEX
        // executed first, the column drop is plain.
        if (destructive && physical != null) {
            for (PhysicalSchema.PhysicalColumn column : physical.columns().values()) {
                String columnLower = lower(column.name());
                if (declaredColumns.contains(columnLower) || renameSourceColumns.contains(columnLower)) {
                    continue;
                }
                addIfRendered(out, renderedFieldChange(dialect,
                        SysDdlContextBuilder.forAlter(model, List.of(), List.of(), List.of(),
                                List.of(undeclaredColumn(model, column.name()))),
                        RenderedDdl.Kind.DROP_COLUMN,
                        "DROP COLUMN " + column.name() + " ON " + table + " " + tag + " undeclared"));
            }
        }
    }

    /**
     * Resolve which physical table this model's convergence works against, planning the
     * genesis CREATE or declared RENAME on the way.
     *
     * @return the table name to use for facts lookups, or {@code null} when per-column
     *         planning must not run (genesis planned, or nothing to create from)
     */
    private String planTableIdentity(SysModel model, SchemaDiff.Modification<SysModel> modelMod,
                                     PhysicalSchema facts, String tag, List<RenderedDdl> out) {
        String table = effectiveTableName(model);
        String oldTable = priorTableName(model, modelMod);
        boolean declaredRename = oldTable != null
                && ((modelMod != null && modelMod.kind() == SchemaDiff.Kind.RENAME)
                        || (modelMod == null && StringUtils.isNotBlank(model.getRenamedFrom())));
        boolean oldExists = oldTable != null && !oldTable.equals(table) && facts.tableExists(oldTable);

        if (facts.tableExists(table)) {
            if (oldExists) {
                throw new IllegalStateException(String.format(
                        "Model %s points at table '%s' while its prior table '%s' also physically exists"
                                + " — a half-applied %s a human must resolve: carry the data into '%s',"
                                + " then DROP TABLE %s, and boot again.",
                        model.getModelName(), table, oldTable,
                        declaredRename ? "rename" : "tableName change", table, oldTable));
            }
            return table;
        }
        if (oldExists) {
            if (!declaredRename) {
                throw new IllegalStateException(String.format(
                        "Model %s changed tableName '%s' -> '%s' without a declared rename while '%s'"
                                + " physically exists. Creating '%s' would silently divorce the data."
                                + " Either rename manually first — ALTER TABLE %s RENAME TO %s; — and"
                                + " boot again, or move the old table away if '%s' really is new.",
                        model.getModelName(), oldTable, table, oldTable, table, oldTable, table, table));
            }
            out.add(RenderedDdl.of(RenderedDdl.Kind.DECLARED_TABLE_RENAME,
                    "model " + model.getModelName() + " tableName " + oldTable + " -> " + table
                            + " (declared renamedFrom)",
                    "ALTER TABLE " + oldTable + " RENAME TO " + table + ";"));
            return oldTable;
        }
        return null;   // genesis (or nothing) — planned below
    }

    /** The physical table a rename-in-flight may still be under, or {@code null}. */
    private static String priorTableName(SysModel model, SchemaDiff.Modification<SysModel> modelMod) {
        if (modelMod != null) {
            return effectiveTableName(modelMod.fromDb());
        }
        if (StringUtils.isNotBlank(model.getRenamedFrom())) {
            // Drift healing after the rows already moved on: the prior table is the
            // snake_case derivation (a custom prior tableName is only recoverable from the
            // diff pairing, which covers the normal rename boot).
            return StringTools.toUnderscoreCase(model.getRenamedFrom());
        }
        return null;
    }

    /** Plan the genesis CREATE for a physically missing table. Shared by both lanes. */
    private void planGenesis(DdlDialect dialect, SysModel model, List<SysField> modelFields,
                             List<SysModelIndex> modelIndexes, String tag, List<RenderedDdl> out) {
        ModelDdlCtx ctx = SysDdlContextBuilder.forCreate(model, modelFields, modelIndexes);
        if (ctx.getCreatedFields().isEmpty()) {
            log.warn("DdlOrchestrator: {} table {} is missing but the code definition has no stored "
                    + "fields to create it from", tag, ctx.getTableName());
            return;
        }
        out.add(RenderedDdl.of(RenderedDdl.Kind.CREATE_TABLE,
                "CREATE TABLE " + ctx.getTableName() + " " + tag + " genesis",
                dialect.createTableDDL(ctx).toString()));
    }

    private void planIndexConvergence(DdlDialect dialect, SysModel model, List<SysField> modelFields,
                                      List<SysModelIndex> modelIndexes, DdlPolicy.IndexOps diffIndexes,
                                      PhysicalSchema.PhysicalTable physical, boolean destructive,
                                      String tag, List<RenderedDdl> out) {
        Map<String, String> fieldToColumn = new HashMap<>();
        for (SysField field : modelFields) {
            fieldToColumn.put(field.getFieldName(), effectiveColumnName(field));
        }
        Set<String> rebuilds = diffIndexes.updated().stream()
                .map(i -> lower(i.getIndexName()))
                .collect(Collectors.toSet());
        Set<String> declaredNames = new HashSet<>();
        for (SysModelIndex index : modelIndexes) {
            declaredNames.add(lower(index.getIndexName()));
            if (rebuilds.contains(lower(index.getIndexName()))) {
                // A definition change rebuilds: DROP INDEX + ADD INDEX, two statements executed
                // and classified separately (a missing index on the DROP half degrades and the
                // ADD still runs).
                renderIndexChange(dialect,
                        SysDdlContextBuilder.forIndexChanges(model, fieldToColumn,
                                List.of(), List.of(index), List.of()),
                        RenderedDdl.Kind.ALTER_INDEX,
                        "REBUILD INDEX " + index.getIndexName(), out);
                continue;
            }
            if (physical == null || !IndexNameCompat.declaredIndexExists(physical, index.getIndexName())) {
                renderIndexChange(dialect,
                        SysDdlContextBuilder.forIndexChanges(model, fieldToColumn,
                                List.of(index), List.of(), List.of()),
                        RenderedDdl.Kind.ALTER_INDEX,
                        "ADD INDEX " + index.getIndexName() + " " + tag, out);
            }
        }
        if (destructive && physical != null) {
            for (String indexName : physical.indexNames()) {
                if (IndexNameCompat.matchesAnyDeclared(indexName, declaredNames)
                        || IndexNameCompat.isPrimaryKeyIndex(indexName)) {
                    continue;
                }
                SysModelIndex ghost = new SysModelIndex();
                ghost.setModelName(model.getModelName());
                ghost.setIndexName(indexName);
                renderIndexChange(dialect,
                        SysDdlContextBuilder.forIndexChanges(model, Map.of(),
                                List.of(), List.of(), List.of(ghost)),
                        RenderedDdl.Kind.DROP_INDEX,
                        "DROP INDEX " + indexName + " ON " + effectiveTableName(model)
                                + " " + tag + " undeclared", out);
            }
        }
    }

    /** A placeholder {@link SysField} for dropping a physical column no declaration owns. */
    private static SysField undeclaredColumn(SysModel model, String columnName) {
        SysField ghost = new SysField();
        ghost.setModelName(model.getModelName());
        ghost.setFieldName(columnName);
        ghost.setColumnName(columnName);
        ghost.setFieldType(FieldType.STRING);   // any stored type — only the column name renders
        return ghost;
    }

    /** Render one field-change unit, or {@code null} when the ctx carries no stored change. */
    private RenderedDdl renderedFieldChange(DdlDialect dialect, ModelDdlCtx ctx,
                                            RenderedDdl.Kind kind, String label) {
        if (!ctx.isHasAlterTableChanges()) {
            return null;
        }
        String sql = dialect.alterTableDDL(ctx).toString().trim();
        return sql.isEmpty() ? null : RenderedDdl.of(kind, label, sql);
    }

    private static void addIfRendered(List<RenderedDdl> out, RenderedDdl unit) {
        if (unit != null) {
            out.add(unit);
        }
    }

    /** Whether the declared physical shape carries a real width bound (see the WIDEN trigger note). */
    private static boolean isBoundedDeclaredWidth(SysField field) {
        FieldType physical = SysDdlContextBuilder.resolvePhysicalFieldType(field);
        return physical != FieldType.TEXT
                && physical != FieldType.JSON
                && physical != FieldType.DTO;
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
                DdlOrchestrator: {} operation(s) not auto-executed (data-bearing changes: DROP / RENAME).
                To apply manually:
                {}""", deferred.size(), block.indent(4).stripTrailing());
    }

    // ---- metadata-only rendering ----------------------------------------

    /**
     * Render the DDL for a diff <b>without executing anything</b> — the render step behind
     * {@link #apply}. Returns units in execution order: table renames first (declared →
     * auto RENAME TABLE, undeclared → warn), then per-model CREATE, per-change ALTERs
     * (column adds / modifies / declared renames, then index adds / rebuilds) and
     * per-model DROP hints (warn).
     */
    private List<RenderedDdl> render(SchemaDiff diff, List<SysModel> allCodeModels,
                                     List<SysField> allCodeFields) {
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
            addIfRendered(out, renderedFieldChange(dialect,
                    SysDdlContextBuilder.forAlter(model, List.of(field), List.of(), List.of(), List.of()),
                    RenderedDdl.Kind.ALTER_TABLE,
                    "ADD COLUMN " + columnLabel(model, field)));
        }
        for (SysField field : op.fields().updated()) {
            addIfRendered(out, renderedFieldChange(dialect,
                    SysDdlContextBuilder.forAlter(model, List.of(), List.of(field), List.of(), List.of()),
                    RenderedDdl.Kind.ALTER_TABLE,
                    "MODIFY COLUMN " + columnLabel(model, field)));
        }
        for (DdlPolicy.FieldRename rename : op.fields().renamed()) {
            addIfRendered(out, renderedFieldChange(dialect,
                    SysDdlContextBuilder.forAlter(model, List.of(), List.of(), List.of(rename), List.of()),
                    RenderedDdl.Kind.DECLARED_COLUMN_RENAME,
                    "CHANGE COLUMN " + rename.oldColumnName() + " -> "
                            + columnLabel(model, rename.field())));
        }
        renderIndexChanges(dialect, op, modelFieldToColumn, out);
    }

    private static String columnLabel(SysModel model, SysField field) {
        return effectiveColumnName(field) + " ON " + effectiveTableName(model);
    }

    private static String effectiveColumnName(SysField field) {
        return SysDdlContextBuilder.resolveColumnName(field);
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
                    RenderedDdl.Kind.ALTER_INDEX,
                    "ADD INDEX " + index.getIndexName(), out);
        }
        // A definition change rebuilds: DROP INDEX + ADD INDEX, two statements executed
        // and classified separately (a missing index on the DROP half degrades via
        // DdlErrorClassifier.isIndexDropAlreadyApplied and the ADD still runs).
        for (SysModelIndex index : op.indexes().updated()) {
            renderIndexChange(dialect,
                    SysDdlContextBuilder.forIndexChanges(op.model(), fieldToColumn,
                            List.of(), List.of(index), List.of()),
                    RenderedDdl.Kind.ALTER_INDEX,
                    "REBUILD INDEX " + index.getIndexName(), out);
        }
    }

    private void renderIndexChange(DdlDialect dialect, ModelDdlCtx ctx, RenderedDdl.Kind kind,
                                   String label, List<RenderedDdl> out) {
        if (!ctx.isHasIndexChanges() || skipUnbuildableTrigram(dialect, ctx)) {
            return;
        }
        String sql = dialect.alterIndexDDL(ctx).toString().trim();
        if (!sql.isEmpty()) {
            out.add(RenderedDdl.of(kind, label, sql));
        }
    }

    /**
     * Drop a trigram index from the plan when the database cannot build one — see
     * {@link TrigramCapability} for why that is a skip and not a boot failure.
     *
     * <p>Guarding here rather than at each caller is deliberate: every index statement in both
     * lanes funnels through this method, so one check covers the convergence planner and the
     * diff planner alike and cannot be forgotten when a third caller appears. Only the CREATE
     * side is guarded — DROPPING a GIN index needs no extension, and refusing to drop one would
     * strand it forever.
     */
    private boolean skipUnbuildableTrigram(DdlDialect dialect, ModelDdlCtx ctx) {
        List<IndexDdlCtx> creating = new ArrayList<>(ctx.getCreatedIndexes());
        creating.addAll(ctx.getUpdatedIndexes());
        creating.addAll(ctx.getRenamedIndexes());
        boolean trigram = creating.stream().anyMatch(IndexDdlCtx::isTrigram);
        if (!trigram || capability(dialect).available()) {
            return false;
        }
        creating.stream().filter(IndexDdlCtx::isTrigram).forEach(
                index -> capability(dialect).warnUnavailable(index.getIndexName(), ctx.getTableName()));
        return true;
    }

    private TrigramCapability capability(DdlDialect dialect) {
        if (trigramCapability == null) {
            trigramCapability = new TrigramCapability(jdbcTemplate, dialect.getDatabaseType());
        }
        return trigramCapability;
    }

    private static void addAllFieldMappings(Map<String, String> target, List<SysField> fields) {
        for (SysField f : fields) {
            if (f.getFieldName() != null && f.getColumnName() != null) {
                target.put(f.getFieldName(), f.getColumnName());
            }
        }
    }

    /**
     * Table renames on the metadata-only lane, two flavours:
     * <ul>
     *   <li><b>Declared</b> ({@code kind == RENAME}, the {@code renamedFrom} attribute on the
     *       model): the intent and the data-preserving target are explicit, so the
     *       {@code RENAME TABLE old TO new} <b>auto-executes</b>.</li>
     *   <li><b>Undeclared</b> ({@code kind == MODIFY}, a bare {@code tableName}-attribute
     *       change): could equally be a silent data divorce, so it stays
     *       <b>warn-only</b> with copy-paste SQL — the same risk class as DROP. (The
     *       convergence lane, which can consult the physical facts, fails the boot on the
     *       ambiguous case instead.)</li>
     * </ul>
     * A declared model rename's fields / indexes were re-keyed by the
     * {@link DiffEngine} cascade, so they show no
     * churn here; the row-side {@code modelName} cascade is done by the writer.
     */
    private void renderTableRenames(SchemaDiff diff, List<RenderedDdl> out) {
        for (SchemaDiff.Modification<SysModel> mod : diff.models().modified()) {
            if (Boolean.TRUE.equals(mod.fromCode().getProjection())) {
                // A projection owns no table: a tableName change repoints it at a different
                // owner's table (row-only), it does not rename the physical table.
                continue;
            }
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

    private static String effectiveTableName(SysModel model) {
        return SysDdlContextBuilder.resolveTableName(model);
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
     * renamed / rebuilt / converged schema: a {@code CHANGE COLUMN} whose old column is
     * gone ({@code DECLARED_COLUMN_RENAME}), a {@code RENAME TABLE} whose old table is
     * gone ({@code DECLARED_TABLE_RENAME}), the DROP half of an index rebuild whose index
     * is gone ({@code ALTER_INDEX}), and the convergence lane's undeclared-column /
     * undeclared-index drops whose target vanished between snapshot and execution
     * ({@code DROP_COLUMN} / {@code DROP_INDEX}). Scoping by kind keeps a genuine
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
            case ALTER_INDEX, DROP_INDEX -> DdlErrorClassifier.isIndexDropAlreadyApplied(e);
            case DROP_COLUMN -> DdlErrorClassifier.isColumnDropAlreadyApplied(e);
            default -> false;
        };
    }

    private static String lower(String identifier) {
        return identifier == null ? null : identifier.toLowerCase(Locale.ROOT);
    }

    // ---- dialect ------------------------------------------------------

    /** Package-private alongside {@link #planConvergence} for the plan-order test. */
    DdlDialect resolveDialect() {
        DatabaseType type = DBUtil.parseDatabaseType(datasourceUrl);
        return DdlDialectFactory.create(type, metadataResolver);
    }
}
