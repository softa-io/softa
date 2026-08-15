package io.softa.starter.permission.service;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.AccessType;
import io.softa.starter.permission.index.EndpointIndex;
import io.softa.starter.permission.spi.PermissionInfo;
import io.softa.starter.permission.spi.PermissionSnapshotProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The endpoint gate answers "may this caller do X to this model" by matching a URL. The file endpoints
 * have no URL it can match — their model is a request parameter, which is why they are whitelisted —
 * and the row check they do instead cannot stand in for it: checkIdAccess reads the same scope whatever
 * access type it is given, so on its own a read-only caller passes an UPDATE check on every row they
 * can see. These pin the replacement question.
 */
class ModelActionGrantTest {

    private EndpointIndex index;

    /**
     * The index is stubbed rather than built: what is under test is the question this class asks it —
     * which model+action maps to which URL, and what an empty answer means. That the URL itself is the
     * one the index really derives is pinned next door, by EndpointIndexStandardDerivationTest's
     * `/Employee/updateOne` → employee.update.
     */
    @BeforeEach
    void setUp() {
        index = mock(EndpointIndex.class);
        when(index.lookup("/Employee/updateOne", "POST")).thenReturn(Set.of("employee.update"));
        when(index.lookup("/Department/updateOne", "POST")).thenReturn(Set.of());
    }

    private PermissionServiceImpl serviceFor(PermissionInfo pi) {
        PermissionSnapshotProvider provider = mock(PermissionSnapshotProvider.class);
        when(provider.get(anyLong(), anyLong())).thenReturn(pi);
        return new PermissionServiceImpl(provider, null, null, null, null, index);
    }

    private boolean askAsUser(PermissionInfo pi, AccessType accessType) {
        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(42L);
        return ContextHolder.callWith(ctx,
                () -> serviceFor(pi).hasModelActionGrant("Employee", accessType));
    }

    @Test
    void holdingTheModelsUpdatePermissionGrantsIt() {
        PermissionInfo pi = new PermissionInfo();
        pi.setPermissions(Set.of("employee.update"));

        assertThat(askAsUser(pi, AccessType.UPDATE)).isTrue();
    }

    @Test
    void aReadOnlyCallerIsRefused() {
        PermissionInfo pi = new PermissionInfo();
        // Holds something, just not the update of this model — the case checkIdAccess cannot see.
        pi.setPermissions(Set.of("employee.view"));

        assertThat(askAsUser(pi, AccessType.UPDATE)).isFalse();
    }

    @Test
    void aModelNoPermissionCoversIsNotThereforeForbidden() {
        PermissionInfo pi = new PermissionInfo();
        pi.setPermissions(Set.of("employee.view"));

        Context ctx = new Context();
        ctx.setTenantId(1L);
        ctx.setUserId(42L);
        // Nothing is registered for Department, so the whitelist that opened the endpoint governs it —
        // denying here would break every model whose CRUD nobody wrote a permission for.
        boolean granted = ContextHolder.callWith(ctx,
                () -> serviceFor(pi).hasModelActionGrant("Department", AccessType.UPDATE));

        assertThat(granted).isTrue();
    }

    @Test
    void anAdminIsNeverRefused() {
        PermissionInfo pi = new PermissionInfo();
        pi.setRoleCodes(Set.of("SUPER_ADMIN"));
        pi.setPermissions(Set.of());

        assertThat(askAsUser(pi, AccessType.UPDATE)).isTrue();
    }
}
