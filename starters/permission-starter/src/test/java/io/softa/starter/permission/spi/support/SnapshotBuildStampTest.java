package io.softa.starter.permission.spi.support;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.service.CacheService;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.permission.spi.PermissionInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A cached snapshot is served only to the build that computed it. During a rolling deploy the instance being
 * replaced computes snapshots from its own startup state over rows the new build has already brought up to date;
 * the new build must compute its own instead of serving those for the rest of their TTL.
 */
class SnapshotBuildStampTest {

    private static final String THIS_BUILD = "2.9.0@2026-10-28T01:00:00Z";
    private static final String KEY = "perm:7:user:42";
    private static final String MARK = "dept-paths-mark:7";

    private final CacheService cache = mock(CacheService.class);
    private final DefaultPermissionSnapshotProvider provider = spy(new DefaultPermissionSnapshotProvider(
            cache, mock(ModelService.class), null, () -> null, List.of(), List.of(), THIS_BUILD));

    @Test
    void aSnapshotThisBuildComputedIsServedFromTheCache() {
        PermissionInfo cached = stamped(THIS_BUILD);
        when(cache.get(KEY, PermissionInfo.class)).thenReturn(cached);

        assertSame(cached, provider.get(7L, 42L));
        verify(provider, never()).doLoadFromDb(anyLong(), anyLong());
    }

    @Test
    void aSnapshotAnotherBuildComputedIsComputedAgainAndReplaced() {
        when(cache.get(KEY, PermissionInfo.class)).thenReturn(stamped("2.8.4@2026-10-20T01:00:00Z"));
        PermissionInfo fresh = new PermissionInfo();
        doReturn(fresh).when(provider).doLoadFromDb(7L, 42L);

        PermissionInfo served = provider.get(7L, 42L);

        assertSame(fresh, served);
        assertEquals(THIS_BUILD, served.getBuildStamp());
        verify(cache).save(eq(KEY), same(fresh), eq(3600));
    }

    @Test
    void aSnapshotCachedBeforeStampsExistedIsComputedAgain() {
        when(cache.get(KEY, PermissionInfo.class)).thenReturn(stamped(null));
        doReturn(new PermissionInfo()).when(provider).doLoadFromDb(7L, 42L);

        assertEquals(THIS_BUILD, provider.get(7L, 42L).getBuildStamp());
    }

    @Test
    void aSnapshotBuiltWhileADepartmentMovedDoesNotStand() {
        // Read the old paths, then the move's clear ran, then this save: it must not stand for the whole TTL.
        when(cache.get(MARK)).thenReturn("before", "after");
        doReturn(new PermissionInfo()).when(provider).doLoadFromDb(7L, 42L);

        provider.get(7L, 42L);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(cache);
        order.verify(cache).save(eq(KEY), org.mockito.ArgumentMatchers.any(PermissionInfo.class), eq(3600));
        order.verify(cache).clear(KEY);
    }

    @Test
    void aSnapshotBuiltWithNoMoveStands() {
        when(cache.get(MARK)).thenReturn("same", "same");
        doReturn(new PermissionInfo()).when(provider).doLoadFromDb(7L, 42L);

        provider.get(7L, 42L);

        verify(cache, never()).clear(KEY);
    }

    private static PermissionInfo stamped(String buildStamp) {
        PermissionInfo info = new PermissionInfo();
        info.setBuildStamp(buildStamp);
        return info;
    }
}
