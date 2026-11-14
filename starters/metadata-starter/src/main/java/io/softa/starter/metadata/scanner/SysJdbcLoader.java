package io.softa.starter.metadata.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.softa.starter.metadata.scanner.annotation.AnnotationScanResult;
import io.softa.starter.metadata.catalog.SysCatalog;
import io.softa.starter.metadata.catalog.SysColumn;
import io.softa.starter.metadata.entity.*;

/**
 * Pure-JDBC reader of the 5 annotation-managed {@code sys_*} tables, returning
 * the current {@link AnnotationScanResult} — the from-db side for
 * {@code DiffEngine}. Rows are matched to the from-code side purely by business
 * key (modelName / fieldName / optionSetCode / itemCode + {@code renamedFrom}).
 *
 * <p>The SELECT column list and row mapping are derived from {@link SysCatalog}
 * (the entity's own {@code @Model} / {@code @Field}); the surrogate {@code id}
 * and {@code app_code} columns are read via the catalog's dedicated accessors —
 * {@code id} so a declared-rename UPDATE can target the existing row, {@code
 * app_code} for the boot identity guard — but neither is diffed.
 *
 * <p>Bypasses {@code SysModelService} / {@code ModelManager} so the scanner can
 * run in {@code AppStartup.afterPropertiesSet()} <b>before</b> the runtime
 * metadata caches are initialized. This is also what makes the "{@code SysModel}
 * describes {@code SysModel} itself" self-reference non-circular — we never read
 * the metadata model to read the metadata catalog.
 *
 * <p>Two read contracts:
 * <ul>
 *   <li>{@link #load()} — <b>strict</b>, for the scanner (write path). Any
 *       {@code BadSqlGrammarException} propagates and fails boot: "cannot read
 *       the current state" must never be degraded to "current state is empty"
 *       on a path that is about to reconcile writes against that state — the
 *       fabricated empty baseline would classify the whole existing catalog
 *       as added.</li>
 *   <li>{@link #loadLenient()} — for the read-only checker. A grammar failure
 *       (most likely a {@code sys_*} table absent on an older clone) degrades to
 *       an empty state with a WARN; the checker then reports everything as
 *       drift, which is the correct, harmless signal.</li>
 * </ul>
 */
@Slf4j
public final class SysJdbcLoader {

    private final JdbcTemplate jdbcTemplate;

    public SysJdbcLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Strict read for the scanner (write path): load all rows of the five
     * {@code sys_*} tables.
     *
     * @throws BadSqlGrammarException when the current state cannot be read —
     *         the scanner must refuse to reconcile rather than diff against a
     *         fabricated empty baseline
     */
    public AnnotationScanResult load() {
        return doLoad();
    }

    /**
     * Lenient read for the read-only checker: a grammar failure degrades to
     * {@link AnnotationScanResult#empty()} with a WARN (everything then surfaces
     * as drift, which is the desired read-only signal).
     */
    public AnnotationScanResult loadLenient() {
        try {
            return doLoad();
        } catch (BadSqlGrammarException e) {
            log.warn("SysJdbcLoader: SELECT failed — the sys_* catalog schema is behind the "
                    + "entities on this classpath. A boot with a non-empty scanner-scope converges "
                    + "it automatically (catalog reconcile); in checker-only environments apply the "
                    + "release's migration SQL. Treating from-db state as empty.", e);
            return AnnotationScanResult.empty();
        }
    }

    private AnnotationScanResult doLoad() {
        return new AnnotationScanResult(
                loadEntity(SysModel.class),
                loadEntity(SysField.class),
                loadEntity(SysOptionSet.class),
                loadEntity(SysOptionItem.class),
                loadEntity(SysModelIndex.class));
    }

    private <E> List<E> loadEntity(Class<E> type) {
        SysCatalog.SysTable<E> table = SysCatalog.of(type);
        List<SysColumn<E>> cols = new ArrayList<>(table.keys());
        cols.addAll(table.data());
        if (table.appCodeColumn() != null) {
            cols.add(table.appCodeColumn());    // identity read for the boot guard, never diffed
        }
        if (table.idColumn() != null) {
            cols.add(table.idColumn());         // surrogate id — targets a declared-rename UPDATE, never diffed
        }
        String sql = "SELECT "
                + cols.stream().map(SysColumn::column).collect(Collectors.joining(", "))
                + " FROM " + table.table();
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            E e = instantiate(type);
            for (SysColumn<E> col : cols) {
                col.read(e, rs);
            }
            return e;
        });
    }

    private static <E> E instantiate(Class<E> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot instantiate " + type.getName(), ex);
        }
    }
}
