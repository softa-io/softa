package io.softa.starter.permission.validation;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.validation.WriteContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * An employee's cached identity follows a write that goes straight through the model service, which no domain
 * event of the application sees: without this, a person moved that way reads their old department for a month.
 */
class EmployeeIdentityCacheEvictorTest {

    private final CacheService cache = mock(CacheService.class);
    private final EmployeeIdentityCacheEvictor evictor = new EmployeeIdentityCacheEvictor(cache);

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    private static Map<String, Object> stored() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 7L);
        row.put("tenantId", 1L);
        row.put("userId", 100L);
        row.put("departmentId", 5L);
        return row;
    }

    private void update(Map<String, Object> patch) {
        Map<String, Object> merged = stored();
        merged.putAll(patch);
        evictor.validateUpdate(WriteContext.of("Employee", AccessType.UPDATE, merged, patch, stored()));
    }

    @Test
    void movingAnEmployeeDropsTheirCachedIdentity() {
        update(Map.of("departmentId", "6"));

        verify(cache).clear("emp-info:100");
        verify(cache).clear("perm:1:user:100");
    }

    @Test
    void aWriteThatMovesNobodyDropsNothing() {
        update(Map.of("departmentId", "5"));
        update(Map.of("fullName", "Someone"));

        verify(cache, never()).clear(anyString());
    }

    @Test
    void anEmployeeWithNoUserHasNothingToDrop() {
        Map<String, Object> row = stored();
        row.remove("userId");
        Map<String, Object> patch = Map.of("departmentId", 6L);
        evictor.validateUpdate(WriteContext.of("Employee", AccessType.UPDATE, row, patch, row));

        verify(cache, never()).clear(anyString());
    }

    @Test
    void relinkingAUserDropsBothUsers() {
        update(Map.of("userId", 200L));

        verify(cache).clear("emp-info:100");
        verify(cache).clear("emp-info:200");
    }

    @Test
    void aFailingCacheDoesNotFailTheWrite() {
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(cache).clear(anyString());

        update(Map.of("departmentId", 6L));
    }

    @Test
    void insideATransactionItWaitsForTheCommit() {
        TransactionSynchronizationManager.initSynchronization();

        update(Map.of("departmentId", 6L));
        verify(cache, never()).clear(anyString());

        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(cache).clear("emp-info:100");
    }

    @Test
    void onlyTheEmployeeModelIsWatched() {
        assertThat(evictor.supports("Employee")).isTrue();
        assertThat(evictor.supports("Department")).isFalse();
    }
}
