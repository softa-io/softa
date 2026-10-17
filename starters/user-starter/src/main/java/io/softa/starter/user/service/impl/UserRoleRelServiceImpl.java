package io.softa.starter.user.service.impl;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.entity.UserRoleRel;
import io.softa.starter.user.event.UserRoleRelChangedEvent;
import io.softa.starter.user.service.RoleService;
import io.softa.starter.user.service.UserRoleRelService;

/**
 * UserRoleRel Model Service Implementation.
 *
 * <p>Guards the two roles somebody must always hold — {@code SUPER_ADMIN} and
 * {@code TENANT_ADMIN} — against removal of their last holder. Without this
 * guard an admin could revoke {@code SUPER_ADMIN} from every user via the
 * generic ORM delete, locking everyone out of role / permission management.
 * Other built-in roles are revocable down to zero holders.
 * The check runs in {@link #deleteById} / {@link #deleteByIds} / before
 * {@link #deleteByFilters}, so it covers all delete paths whether they go
 * through bulk revoke, the wizard "rewrite" flow, or a custom service call.
 */
@Slf4j
@Service
public class UserRoleRelServiceImpl extends EntityServiceImpl<UserRoleRel, Long> implements UserRoleRelService {

    // @Lazy avoids a bidirectional bean-init cycle with RoleService, which
    // can transitively pull this service back in via permission helpers.
    private final RoleService roleService;
    private final ApplicationEventPublisher events;

    @Autowired
    public UserRoleRelServiceImpl(@Lazy RoleService roleService, ApplicationEventPublisher events) {
        this.roleService = roleService;
        this.events = events;
    }

    @Override
    public Long createOne(UserRoleRel entity) {
        guardSuperAdminBind(entity == null ? List.of() : List.of(entity));
        Long id = super.createOne(entity);
        publishChange(Set.of(entity.getUserId()));
        return id;
    }

    @Override
    public List<Long> createList(List<UserRoleRel> entities) {
        guardSuperAdminBind(entities);
        List<Long> ids = super.createList(entities);
        if (entities != null) {
            Set<Long> userIds = new HashSet<>();
            for (UserRoleRel e : entities) if (e.getUserId() != null) userIds.add(e.getUserId());
            publishChange(userIds);
        }
        return ids;
    }

    @Override
    public boolean deleteById(Long id) {
        Set<Long> affected = collectAffectedUserIds(List.of(id));
        guardSystemRoleHolderRemoval(List.of(id));
        boolean ok = super.deleteById(id);
        publishChange(affected);
        return ok;
    }

    @Override
    public boolean deleteByIds(List<Long> ids) {
        Set<Long> affected = collectAffectedUserIds(ids);
        guardSystemRoleHolderRemoval(ids);
        boolean ok = super.deleteByIds(ids);
        publishChange(affected);
        return ok;
    }

    @Override
    public boolean deleteByFilters(Filters filters) {
        // Resolve which rel rows would be affected, then route through the
        // id-based guard so all delete paths share one check.
        List<UserRoleRel> affected = searchList(filters);
        Set<Long> affectedUserIds = new HashSet<>(affected.size());
        for (UserRoleRel r : affected) if (r.getUserId() != null) affectedUserIds.add(r.getUserId());
        if (!affected.isEmpty()) {
            guardSystemRoleHolderRemoval(affected.stream().map(UserRoleRel::getId).toList());
        }
        boolean ok = super.deleteByFilters(filters);
        publishChange(affectedUserIds);
        return ok;
    }

    /** Snapshot user ids that will be touched before super.delete runs — the
     *  rel rows are gone by the time we'd want to publish, so capture first. */
    private Set<Long> collectAffectedUserIds(List<Long> relIds) {
        if (relIds == null || relIds.isEmpty()) return Set.of();
        List<UserRoleRel> rels = searchList(new Filters().in(UserRoleRel::getId, relIds));
        Set<Long> userIds = new HashSet<>(rels.size());
        for (UserRoleRel r : rels) if (r.getUserId() != null) userIds.add(r.getUserId());
        return userIds;
    }

    private void publishChange(Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return;
        Long tenantId = ContextHolder.getContext() == null ? null
                : ContextHolder.getContext().getTenantId();
        if (tenantId == null) {
            // Publisher without a bound tenant context.
            // Downstream evictBatch (see PermissionCacheInvalidatorImpl) skips
            // when tenantId is null → cache stays stale for the affected
            // users up to 1h TTL. Log loudly so operators can trace which
            // code path (scheduled job, async pool, test fixture, migration)
            // is missing a ContextHolder.callWith(bootstrapCtx, ...) wrapper.
            // We do NOT throw — the write itself is legitimate; refusing to
            // publish would silently swallow the miss instead of surfacing it.
            log.warn("UserRoleRel change publisher — null tenantId; cache eviction "
                    + "will be skipped for userIds={}. Wrap the caller in "
                    + "ContextHolder.callWith(bootstrapCtx, ...) to fix.", userIds);
        }
        events.publishEvent(UserRoleRelChangedEvent.forUsers(tenantId, userIds));
    }

    /**
     * Reject binding the {@code SUPER_ADMIN} role via the API — it is managed by ops only (seed / DB);
     * no application flow legitimately binds it. TENANT_ADMIN is deliberately NOT blocked here:
     * {@link io.softa.starter.user.provisioning.AdminProvisioningService} binds it during tenant
     * provisioning, which routes through this same {@code createOne}.
     *
     * <p>This covers the typed service only. The rule that holds on every write path — generic model
     * routes and updates included — and that also limits who may bind {@code TENANT_ADMIN}, is
     * {@link io.softa.starter.user.service.RoleBindValidator}.
     */
    private void guardSuperAdminBind(List<UserRoleRel> entities) {
        if (entities == null || entities.isEmpty()) return;
        Set<Long> roleIds = new HashSet<>();
        for (UserRoleRel e : entities) {
            if (e != null && e.getRoleId() != null) roleIds.add(e.getRoleId());
        }
        if (roleIds.isEmpty()) return;
        boolean bindsSuperAdmin = !roleService.searchList(
                        new Filters().in(Role::getId, roleIds).eq(Role::getCode, RoleConstant.CODE_SUPER_ADMIN))
                .isEmpty();
        if (bindsSuperAdmin) {
            throw new BusinessException(
                    "Cannot bind the SUPER_ADMIN role via the API; it is managed by ops only.");
        }
    }

    /**
     * Refuse to strand a role that somebody must always hold — {@code SUPER_ADMIN} and
     * {@code TENANT_ADMIN}, the two whose absence leaves nobody able to administer.
     *
     * <p>Every other built-in role is revocable down to zero holders. It reads as though it should be
     * the same rule, and it was: the check keyed on "has a code", which is true of every reserved
     * role. That is the right test for whether the role ROW may be edited or deleted — it is not the
     * test for whether the role must have a member. Downstream apps seed built-in business roles of
     * their own, and the last HR Admin then could not be revoked, which is a real thing to do when
     * somebody changes jobs.
     *
     * <p>The query still fetches every reserved role in the set and the narrowing happens here, in
     * one readable predicate, rather than inside the filter: reserved roles number a handful, and
     * which of them are protected is a business rule that should not be spelled out in a query.
     */
    private void guardSystemRoleHolderRemoval(List<Long> relIds) {
        if (relIds == null || relIds.isEmpty()) return;

        List<UserRoleRel> rels = searchList(new Filters().in(UserRoleRel::getId, relIds));
        if (rels.isEmpty()) return;
        Set<Long> roleIds = new HashSet<>(rels.size());
        for (UserRoleRel r : rels) {
            if (r.getRoleId() != null) roleIds.add(r.getRoleId());
        }
        if (roleIds.isEmpty()) return;

        List<Role> systemRoles = roleService.searchList(
                new Filters().in(Role::getId, roleIds).isSet(Role::getCode));

        for (Role role : systemRoles) {
            if (!RoleConstant.requiresAtLeastOneHolder(role)) continue;
            long toRemove = rels.stream()
                    .filter(r -> role.getId().equals(r.getRoleId()))
                    .count();
            long currentHolders = count(new Filters().eq(UserRoleRel::getRoleId, role.getId()));
            if (currentHolders - toRemove <= 0) {
                throw new BusinessException(
                        "Cannot revoke the last holder of system role '" + role.getName()
                                + "' (code=" + role.getCode() + ")");
            }
        }
    }
}
