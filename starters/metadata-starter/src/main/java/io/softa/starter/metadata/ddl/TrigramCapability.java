package io.softa.starter.metadata.ddl;

import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import io.softa.framework.orm.enums.DatabaseType;

/**
 * Whether this database can actually build a trigram index right now.
 *
 * <p>{@code IndexType.TRIGRAM} renders as {@code USING gin (col gin_trgm_ops)} on PostgreSQL, which
 * needs the {@code pg_trgm} extension. The framework never installs it: {@code CREATE EXTENSION}
 * generally requires superuser rights that a managed PostgreSQL will not grant to an application
 * role, and a boot-time DDL window is the worst place to discover that. So it is a deployment
 * prerequisite, and this class is how the planner finds out before emitting SQL that would fail.
 *
 * <p><b>The probe deliberately does not ask {@code pg_extension}.</b> That view answers "is the
 * extension installed <i>somewhere</i>", but {@code gin_trgm_ops} only resolves if the schema it was
 * installed into is on the connection's {@code search_path}. A deployment that keeps extensions in
 * their own schema — a common hygiene rule on managed platforms — would pass an
 * {@code extname = 'pg_trgm'} check and then still fail with "operator class does not exist", which
 * is the exact error this check exists to prevent. Asking whether the opclass is <i>visible</i> tests
 * the thing the DDL actually needs.
 *
 * <p>When unavailable the planner <b>skips</b> the index rather than failing the boot, and says so
 * once. Rows stay in {@code sys_model_index}, so the next boot after a DBA installs the extension
 * creates them with no code change. Skipping must never mean "fall back to a B-tree": the physical
 * snapshot compares index <i>names</i> only, so a B-tree wearing a {@code _trgm} name would look
 * converged forever and the search would silently stay a sequential scan.
 */
@Slf4j
final class TrigramCapability {

    /**
     * True when {@code gin_trgm_ops} is resolvable on the current {@code search_path}.
     * {@code pg_opclass_is_visible} is what makes this a visibility test rather than an
     * installed-anywhere test.
     */
    private static final String PROBE = """
            SELECT 1 FROM pg_opclass o
              JOIN pg_am a ON a.oid = o.opcmethod
             WHERE a.amname = 'gin'
               AND o.opcname = 'gin_trgm_ops'
               AND pg_opclass_is_visible(o.oid)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseType databaseType;

    /** Probed at most once per orchestrator: the answer cannot change inside one boot. */
    private Boolean available;

    TrigramCapability(JdbcTemplate jdbcTemplate, DatabaseType databaseType) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseType = databaseType;
    }

    /**
     * @return true when a trigram index can be built. Always false off PostgreSQL — no other
     *     dialect renders one, so there is nothing to check and nothing to warn about.
     */
    boolean available() {
        if (databaseType != DatabaseType.POSTGRESQL) {
            return false;
        }
        if (available == null) {
            available = probe();
        }
        return available;
    }

    /**
     * Any failure reads as "not available". A role locked down enough to be refused
     * {@code pg_catalog} reads must not fail the boot of an application that may not even use a
     * trigram index — and on H2 (which the DDL tests run against in PostgreSQL compatibility mode)
     * the query simply does not resolve, which is the answer we want there anyway.
     */
    private boolean probe() {
        try {
            List<Integer> rows = jdbcTemplate.queryForList(PROBE, Integer.class);
            return !rows.isEmpty();
        } catch (RuntimeException e) {
            log.debug("TrigramCapability: pg_trgm probe failed, treating as unavailable", e);
            return false;
        }
    }

    /** One actionable line per orchestrator run, not one per skipped index. */
    void warnUnavailable(String indexName, String tableName) {
        log.warn("DdlOrchestrator: skipping trigram index {} on {} — the pg_trgm extension is not "
                        + "available on the current search_path, so 'USING gin (... gin_trgm_ops)' "
                        + "would fail. Ask a superuser to run: CREATE EXTENSION pg_trgm; "
                        + "the index is created on the next boot, no code change needed.",
                indexName, tableName);
    }
}
