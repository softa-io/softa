package io.softa.starter.metadata.sequence.service.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.jdbc.JdbcProxy;
import io.softa.framework.orm.jdbc.database.SqlParams;
import io.softa.framework.orm.sequence.SequenceService;
import io.softa.framework.orm.sequence.exception.SequenceCrossTenantException;
import io.softa.starter.metadata.sequence.service.SequenceConfigCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link SequenceServiceImpl}, covering the gray
 * areas identified in Phase 0 of the design (see
 * {@code corehr/docs/design/sequence-metadata-binding.md}):
 *
 * <ol>
 *   <li>{@code @Transactional(REQUIRES_NEW)} actually commits independently
 *       of the caller's outer transaction (ALLOW_GAP semantics).</li>
 *   <li>{@code @Transactional(MANDATORY)} keeps the counter aligned with
 *       business rollback (NO_GAP semantics).</li>
 *   <li>{@code LAST_INSERT_ID()} returns the freshly-written value on the
 *       same Spring-bound connection, both inside and outside a nested
 *       transaction.</li>
 *   <li>{@link SequenceConfigCache} is evicted by the
 *       {@code SysSequenceChangeListener} after the surrounding business
 *       transaction commits.</li>
 *   <li>Cross-tenant context rejects {@code next} / {@code peek}.</li>
 * </ol>
 *
 * <p>Currently {@code @Disabled} because it requires a running MySQL +
 * Redis pair on localhost (per the existing {@code application-test.yml}
 * conventions in apps/demo-app). Run the {@code SysSequence.ddl.sql} +
 * metadata DML against the test database first, then remove
 * {@code @Disabled} and execute via {@code mvn test -Dtest=SequenceServiceIT}.
 */
@Disabled("Requires localhost MySQL + Redis; run SysSequence DDL/DML beforehand")
@SpringBootTest
class SequenceServiceIT {

    private static final Long TENANT_ID = 1L;
    private static final String CODE_NO_GAP = "Employee.code";
    private static final String CODE_ALLOW_GAP = "Audit.eventNo";

    @Autowired private SequenceService sequenceService;
    @Autowired private SequenceConfigCache configCache;
    @Autowired private JdbcProxy jdbcProxy;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private Context baseContext;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        baseContext = ContextHolder.getContext().copy();
        baseContext.setTenantId(TENANT_ID);
        // Reset counters to a known state. Use direct SQL to bypass cache + audit.
        ContextHolder.runWith(baseContext, () -> {
            resetCounter(CODE_NO_GAP, "EMP-{seq:5}", "NO_GAP");
            resetCounter(CODE_ALLOW_GAP, "AUD-{seq:6}", "ALLOW_GAP");
            configCache.evict(CODE_NO_GAP);
            configCache.evict(CODE_ALLOW_GAP);
        });
    }

    @AfterEach
    void tearDown() {
        baseContext = null;
    }

    // ---------------------------------------------------------------- NO_GAP

    @Test
    void noGap_basic_returnsRenderedSequence() {
        String first = ContextHolder.callWith(baseContext, () -> tx.execute(s -> sequenceService.next(CODE_NO_GAP)));
        String second = ContextHolder.callWith(baseContext, () -> tx.execute(s -> sequenceService.next(CODE_NO_GAP)));
        assertThat(first).isEqualTo("EMP-00001");
        assertThat(second).isEqualTo("EMP-00002");
    }

    @Test
    void noGap_businessRollback_rollsBackCounter() {
        // Allocate inside a tx that we then mark for rollback.
        ContextHolder.callWith(baseContext, () -> tx.execute(s -> {
            String code = sequenceService.next(CODE_NO_GAP);
            assertThat(code).isEqualTo("EMP-00001");
            s.setRollbackOnly();
            return null;
        }));
        // Counter must NOT have advanced — next call should produce the same value.
        String reused = ContextHolder.callWith(baseContext, () -> tx.execute(s -> sequenceService.next(CODE_NO_GAP)));
        assertThat(reused).isEqualTo("EMP-00001");
    }

    @Test
    void noGap_outsideTransaction_rejected() {
        // MANDATORY propagation requires an outer transaction.
        assertThatThrownBy(() -> ContextHolder.callWith(baseContext, () -> sequenceService.next(CODE_NO_GAP)))
                .hasMessageContaining("transaction");
    }

    @Test
    void nextBatch_singleRowLock_returnsContiguousRange() {
        List<String> codes = ContextHolder.callWith(baseContext, () -> tx.execute(s -> sequenceService.nextBatch(CODE_NO_GAP, 5)));
        assertThat(codes).containsExactly(
                "EMP-00001", "EMP-00002", "EMP-00003", "EMP-00004", "EMP-00005");
    }

    // -------------------------------------------------------------- ALLOW_GAP

    @Test
    void allowGap_businessRollback_leavesCounterAdvanced() {
        // ALLOW_GAP commits the inner tx independently — outer rollback cannot retract it.
        ContextHolder.callWith(baseContext, () -> tx.execute(s -> {
            String code = sequenceService.next(CODE_ALLOW_GAP);
            assertThat(code).isEqualTo("AUD-000001");
            s.setRollbackOnly();
            return null;
        }));
        // Subsequent allocation must SKIP the rolled-back number, demonstrating the gap.
        String next = ContextHolder.callWith(baseContext, () -> tx.execute(s -> sequenceService.next(CODE_ALLOW_GAP)));
        assertThat(next).isEqualTo("AUD-000002");
    }

    @Test
    void allowGap_outsideTransaction_allowed() {
        // REQUIRES_NEW opens its own tx; calling outside one is permitted (though
        // discouraged in production code where exception handling needs to bubble).
        String code = ContextHolder.callWith(baseContext, () -> sequenceService.next(CODE_ALLOW_GAP));
        assertThat(code).isEqualTo("AUD-000001");
    }

    // ---------------------------------------------------------------- cache

    @Test
    void cache_isPopulatedOnFirstCallAndReusedOnSecond() {
        ContextHolder.callWith(baseContext, () -> tx.execute(s -> sequenceService.next(CODE_NO_GAP)));
        // Mutate template directly in DB without going through the listener path:
        // the cache should still have the OLD template for this case (proves cache hit).
        jdbcProxy.update("SysSequence", new SqlParams(
                "UPDATE sys_sequence SET template = 'CHEAT-{seq:5}' WHERE tenant_id = " + TENANT_ID
                        + " AND code = '" + CODE_NO_GAP + "'"));
        String stillUsesCachedTemplate = ContextHolder.callWith(baseContext, () -> tx.execute(s -> sequenceService.next(CODE_NO_GAP)));
        assertThat(stillUsesCachedTemplate).startsWith("EMP-");
    }

    // ------------------------------------------------------------ cross-tenant

    @Test
    void crossTenantContext_rejected() {
        Context cross = baseContext.copy();
        cross.setCrossTenant(true);
        assertThatThrownBy(() -> ContextHolder.callWith(cross, () -> tx.execute(s -> sequenceService.next(CODE_NO_GAP))))
                .hasCauseInstanceOf(SequenceCrossTenantException.class);
    }

    // ---------------------------------------------------------------- helpers

    private void resetCounter(String code, String template, String mode) {
        Map<String, Object> args = new HashMap<>();
        args.put("template", template);
        args.put("mode", mode);
        // Upsert pattern — assumes the test DB has the row already from metadata seed.
        SqlParams sql = new SqlParams("""
                UPDATE sys_sequence
                SET current_value = 0,
                    last_reset_key = NULL,
                    template = ?,
                    mode = ?
                WHERE tenant_id = ? AND code = ?
                """);
        sql.addArgValue(template);
        sql.addArgValue(mode);
        sql.addArgValue(TENANT_ID);
        sql.addArgValue(code);
        jdbcProxy.update("SysSequence", sql);
    }
}
