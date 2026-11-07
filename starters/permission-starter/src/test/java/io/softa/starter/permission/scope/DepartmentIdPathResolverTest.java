package io.softa.starter.permission.scope;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the per-tenant Redis cache-aside in {@link DepartmentIdPathResolver}:
 * read-through (miss → load the whole tree → populate), hit (no DB / no re-write),
 * batch lookup, cross-tenant isolation via the key, fail-closed when no tenant is
 * bound, and eviction via {@link DepartmentIdPathResolver#evict}.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class DepartmentIdPathResolverTest {

    private static final long TENANT = 9L;
    private static final String KEY = "dept-idpath:9";

    private ModelService modelService;
    private CacheService cacheService;
    private DepartmentIdPathResolver resolver;

    @BeforeEach
    void setUp() {
        modelService = mock(ModelService.class);
        cacheService = mock(CacheService.class);
        resolver = new DepartmentIdPathResolver(modelService, cacheService);
    }

    private static Context tenantCtx() {
        Context ctx = new Context();
        ctx.setTenantId(TENANT);
        return ctx;
    }

    @Test
    void cacheMiss_loadsWholeTree_populatesCache() {
        // get() unstubbed → returns null → miss → load the tree from DB
        when(modelService.searchList(eq("Department"), any(FlexQuery.class)))
                .thenReturn(List.of(Map.of("id", 5L, "idPath", "1/5")));

        Optional<String> out = ContextHolder.callWith(tenantCtx(), () -> resolver.idPathOf(5L));

        assertThat(out).contains("1/5");
        verify(modelService).searchList(eq("Department"), any(FlexQuery.class));
        verify(cacheService).save(eq(KEY), any(), anyInt());
    }

    @Test
    void cacheHit_servesFromCache_noDbNoWrite() {
        doReturn(Map.of(5L, "1/5")).when(cacheService).get(eq(KEY), any(TypeReference.class));

        Optional<String> out = ContextHolder.callWith(tenantCtx(), () -> resolver.idPathOf(5L));

        assertThat(out).contains("1/5");
        verify(modelService, never()).searchList(any(), any(FlexQuery.class));
        verify(cacheService, never()).save(any(), any(), anyInt());
    }

    @Test
    void idPathsOf_batchLookup_dropsUnknown() {
        doReturn(Map.of(5L, "1/5", 7L, "1/7")).when(cacheService).get(eq(KEY), any(TypeReference.class));

        List<String> out = ContextHolder.callWith(tenantCtx(),
                () -> resolver.idPathsOf(List.of(5L, 7L, 9L)));

        assertThat(out).containsExactlyInAnyOrder("1/5", "1/7");
        verify(modelService, never()).searchList(any(), any(FlexQuery.class));
    }

    @Test
    void unknownId_empty() {
        doReturn(Map.of(5L, "1/5")).when(cacheService).get(eq(KEY), any(TypeReference.class));

        Optional<String> out = ContextHolder.callWith(tenantCtx(), () -> resolver.idPathOf(999L));

        assertThat(out).isEmpty();
    }

    @Test
    void noTenantBound_empty_noDbNoCache() {
        // Called outside any ContextHolder scope — no tenant to key the cache.
        assertThat(resolver.idPathOf(5L)).isEmpty();
        verify(modelService, never()).searchList(any(), any(FlexQuery.class));
        verify(cacheService, never()).get(any(), any(TypeReference.class));
    }

    @Test
    void evict_clearsTenantKey() {
        resolver.evict(TENANT);

        verify(cacheService).clear(KEY);
    }

    @Test
    void evict_clearsTheTenantsPermissionSnapshots() {
        resolver.evict(TENANT);

        verify(cacheService).clearByPrefix("perm:" + TENANT + ":");
    }

    @Test
    void evict_snapshotClearFailing_doesNotFailTheCaller() {
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(cacheService).clearByPrefix(anyString());

        resolver.evict(TENANT);

        verify(cacheService).clear(KEY);
    }

    @Test
    void evict_withoutAPathChanged_leavesThePermissionSnapshots() {
        resolver.evict(TENANT, false);

        verify(cacheService).clear(KEY);
        verify(cacheService, never()).clearByPrefix(anyString());
        verify(cacheService, never()).save(eq("dept-paths-mark:" + TENANT), any(), anyInt());
    }

    @Test
    void evict_withAPathChanged_marksThePathsBeforeClearingTheSnapshots() {
        resolver.evict(TENANT, true);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(cacheService);
        order.verify(cacheService).save(eq("dept-paths-mark:" + TENANT), any(), anyInt());
        order.verify(cacheService).clearByPrefix("perm:" + TENANT + ":");
    }

    @Test
    void aTreeThatCouldNotBeReadIsNotCached() {
        when(modelService.searchList(eq("Department"), any(FlexQuery.class))).thenThrow(new IllegalStateException("db down"));

        assertThat(ContextHolder.callWith(tenantCtx(), () -> resolver.idPathOf(1L))).isEmpty();

        verify(cacheService, never()).save(eq(KEY), any(), anyInt());
    }

    @Test
    void aTenantWithNoDepartmentsIsCached() {
        when(modelService.searchList(eq("Department"), any(FlexQuery.class))).thenReturn(List.of());

        assertThat(ContextHolder.callWith(tenantCtx(), () -> resolver.idPathOf(1L))).isEmpty();

        verify(cacheService).save(eq(KEY), eq(Map.of()), anyInt());
    }

    @Test
    void loadTree_readsDisabledDepartmentsToo() {
        when(modelService.searchList(eq("Department"), any(FlexQuery.class))).thenReturn(List.of());

        ContextHolder.callWith(tenantCtx(), () -> resolver.idPathOf(1L));

        org.mockito.ArgumentCaptor<FlexQuery> query = org.mockito.ArgumentCaptor.forClass(FlexQuery.class);
        verify(modelService).searchList(eq("Department"), query.capture());
        org.assertj.core.api.Assertions.assertThat(query.getValue().getFilterControl())
                .usingRecursiveComparison()
                .isEqualTo(io.softa.framework.orm.domain.FilterControl.bypassActiveControl());
    }

    @Test
    void evict_nullTenant_noClear() {
        resolver.evict(null);

        verify(cacheService, never()).clear(anyString());
    }

    /** The roots and every descendant, by whole path segments: {@code 1/50} is not under {@code 1/5}. */
    @Test
    void subtreeIdsOf_takesTheRootsAndTheirDescendantsFromTheCachedTree() {
        doReturn(Map.of(1L, "1", 5L, "1/5", 7L, "1/5/7", 12L, "1/12", 50L, "1/50"))
                .when(cacheService).get(eq(KEY), any(TypeReference.class));

        Set<Long> out = ContextHolder.callWith(tenantCtx(), () -> resolver.subtreeIdsOf(List.of("1/5", "1/12")));

        assertThat(out).containsExactlyInAnyOrder(5L, 7L, 12L);
        verify(modelService, never()).searchList(any(), any(FlexQuery.class));
    }
}
