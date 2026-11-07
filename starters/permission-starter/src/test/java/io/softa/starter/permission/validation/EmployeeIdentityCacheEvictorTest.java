package io.softa.starter.permission.validation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.WriteContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An employee's cached identity follows a write that goes straight through the model service, which no domain
 * event of the application sees: without this, a person moved that way reads their old department for a month.
 */
class EmployeeIdentityCacheEvictorTest {

    private final CacheService cache = mock(CacheService.class);
    @SuppressWarnings("rawtypes")
    private final ModelService modelService = mock(ModelService.class);
    @SuppressWarnings("unchecked")
    private final EmployeeIdentityCacheEvictor evictor = new EmployeeIdentityCacheEvictor(cache, modelService);

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
    void onlyTheModelsTheIdentityIsBuiltFromAreWatched() {
        assertThat(evictor.supports("Employee")).isTrue();
        assertThat(evictor.supports("Department")).isTrue();
        assertThat(evictor.supports("Ticket")).isFalse();
    }

    // ---- who manages a department ---------------------------------------------------------------------

    /** Employees 11 and 12 and their users 111 and 112; employee 13 has no user. */
    @SuppressWarnings("unchecked")
    private void employeesWithUsers() {
        when(modelService.searchList(eq("Employee"), any(FlexQuery.class))).thenReturn(List.of(
                Map.of("id", 11L, "userId", 111L), Map.of("id", 12L, "userId", 112L), Map.of("id", 13L)));
    }

    private static Map<String, Object> department() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 5L);
        row.put("tenantId", 1L);
        row.put("picEmpId", 11L);
        return row;
    }

    private void updateDepartment(Map<String, Object> patch) {
        Map<String, Object> merged = department();
        merged.putAll(patch);
        evictor.validateUpdate(WriteContext.of("Department", AccessType.UPDATE, merged, patch, department()));
    }

    private FlexQuery employeeQuery() {
        org.mockito.ArgumentCaptor<FlexQuery> query = org.mockito.ArgumentCaptor.forClass(FlexQuery.class);
        verify(modelService).searchList(eq("Employee"), query.capture());
        return query.getValue();
    }

    @Test
    void anotherHeadDropsTheCachesOfTheOldAndTheNew() {
        employeesWithUsers();

        updateDepartment(Map.of("picEmpId", "12"));

        verify(cache).clear("emp-info:111");
        verify(cache).clear("perm:1:user:111");
        verify(cache).clear("emp-info:112");
        verify(cache).clear("perm:1:user:112");
        assertThat(employeeQuery().getFilters().toString()).contains("11", "12");
    }

    @Test
    void anotherPartnerCountsToo() {
        employeesWithUsers();

        updateDepartment(Map.of("hrbpEmpId", 12L));

        verify(cache).clear("emp-info:112");
    }

    @Test
    void removingAHeadDropsTheOldOne() {
        employeesWithUsers();
        Map<String, Object> patch = new HashMap<>();
        patch.put("picEmpId", null);

        updateDepartment(patch);

        verify(cache).clear("emp-info:111");
    }

    @Test
    void aDepartmentWriteLeavingItsManagersAloneReadsAndDropsNothing() {
        updateDepartment(Map.of("name", "Ops"));
        updateDepartment(Map.of("picEmpId", "11"));

        verifyNoInteractions(modelService);
        verify(cache, never()).clear(anyString());
    }

    @Test
    void aCreatedDepartmentDropsItsManagers() {
        employeesWithUsers();
        Map<String, Object> row = department();
        row.remove("tenantId");
        Context context = new Context();
        context.setTenantId(9L);

        ContextHolder.runWith(context, () ->
                evictor.validateCreate(WriteContext.of("Department", AccessType.CREATE, row, null)));

        verify(cache).clear("emp-info:111");
        verify(cache).clear("perm:9:user:111");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDeletedDepartmentDropsItsManagers() {
        employeesWithUsers();
        when(modelService.searchList(eq("Department"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("id", 5L, "picEmpId", 11L, "hrbpEmpId", 12L)));
        Context context = new Context();
        context.setTenantId(1L);

        ContextHolder.runWith(context, () -> evictor.validateDelete("Department", List.of(5L)));

        verify(cache).clear("emp-info:111");
        verify(cache).clear("emp-info:112");
    }

    @Test
    void anEmployeeWithNoUserIsSkippedAndTheOthersStillDropped() {
        employeesWithUsers();

        updateDepartment(Map.of("picEmpId", 13L));

        verify(cache).clear("emp-info:111");
        verify(cache, never()).clear("emp-info:null");
    }

    @Test
    void whoManagesADepartmentWaitsForTheCommitInsideATransaction() {
        employeesWithUsers();
        TransactionSynchronizationManager.initSynchronization();

        updateDepartment(Map.of("picEmpId", 12L));
        verify(cache, never()).clear(anyString());

        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(cache).clear("emp-info:111");
    }

    @Test
    void failingToReadTheUsersDoesNotFailTheWrite() {
        when(modelService.searchList(eq("Employee"), any(FlexQuery.class))).thenThrow(new IllegalStateException("down"));

        updateDepartment(Map.of("picEmpId", 12L));

        verify(cache, never()).clear(anyString());
    }
}
