package io.softa.starter.user.service.impl;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.user.constant.RoleConstant;
import io.softa.starter.user.entity.Role;
import io.softa.starter.user.event.RoleNavigationChangedEvent;
import io.softa.starter.user.service.RoleService;

/**
 * Role Model Service Implementation.
 *
 * <p>Guards system-reserved roles (code='SUPER_ADMIN', etc.) against
 * destructive mutations — delete / rename / set inactive / clear code.
 * Catches both Entity and Map-shaped writes since admin UI may send either.
 *
 * <p>Reserved role codes are owned by the system (seeded via SQL / app
 * startup). Admin-created roles must have {@code code = null}; submitting
 * a non-null code through create endpoints would let an admin masquerade
 * as a system role and inherit its bypass — {@link #guardAdminCreatedCode}
 * blocks that path at the service layer. The DB also carries a
 * {@code (tenant_id, code) UNIQUE} index as a belt-and-braces defence
 * against direct SQL writes.
 */
@Slf4j
@Service
public class RoleServiceImpl extends EntityServiceImpl<Role, Long> implements RoleService {

    private static final String USER_ROLE_REL = "UserRoleRel";

    private final ApplicationEventPublisher events;

    public RoleServiceImpl(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public Long createOne(Role entity) {
        guardAdminCreatedCode(entity);
        return super.createOne(entity);
    }

    @Override
    public List<Long> createList(List<Role> entities) {
        if (entities != null) entities.forEach(this::guardAdminCreatedCode);
        return super.createList(entities);
    }

    @Override
    public boolean deleteById(Long id) {
        guardSystemRole(List.of(id), "Delete");
        guardNoMembers(List.of(id));
        boolean ok = super.deleteById(id);
        if (ok && id != null) publishRoleGrantChange(Set.of(id));
        return ok;
    }

    @Override
    public boolean deleteByIds(List<Long> ids) {
        guardSystemRole(ids, "Delete");
        guardNoMembers(ids);
        boolean ok = super.deleteByIds(ids);
        if (ok && ids != null) publishRoleGrantChange(ids);
        return ok;
    }

    @Override
    public boolean updateOne(Role entity) {
        guardSystemMutation(entity);
        boolean ok = super.updateOne(entity);
        if (ok) publishRoleGrantChange(idsOf(entity));
        return ok;
    }

    @Override
    public boolean updateOne(Role entity, boolean ignoreNull) {
        guardSystemMutation(entity);
        boolean ok = super.updateOne(entity, ignoreNull);
        if (ok) publishRoleGrantChange(idsOf(entity));
        return ok;
    }

    @Override
    public boolean updateList(List<Role> entities) {
        if (entities != null) entities.forEach(this::guardSystemMutation);
        boolean ok = super.updateList(entities);
        if (ok) publishRoleGrantChange(idsOf(entities));
        return ok;
    }

    @Override
    public boolean updateList(List<Role> entities, boolean ignoreNull) {
        if (entities != null) entities.forEach(this::guardSystemMutation);
        boolean ok = super.updateList(entities, ignoreNull);
        if (ok) publishRoleGrantChange(idsOf(entities));
        return ok;
    }

    /** Reject when caller (admin UI / API client) submits a non-null code on
     *  create. {@code code} is reserved for system seeds; admin-created roles
     *  must have {@code code = null}. Without this guard, a privileged admin
     *  could POST {@code {"name":"x","code":"SUPER_ADMIN",...}} and inherit
     *  the SUPER_ADMIN bypass on their own row. */
    private void guardAdminCreatedCode(Role entity) {
        if (entity == null) return;
        if (entity.getCode() != null && !entity.getCode().isEmpty()) {
            throw new BusinessException(
                    "Role code is reserved for system roles; admin-created roles must have code=null");
        }
    }

    /** Reject if any id in the batch belongs to a system role. */
    private void guardSystemRole(List<Long> ids, String op) {
        if (ids == null || ids.isEmpty()) return;
        // isSet(code) → only system-reserved roles (admin-created roles have code=null); ANY match is blocked
        // (SUPER_ADMIN / TENANT_ADMIN / any future built-in — none may be deleted via the API).
        List<Role> systemRoles = searchList(
                new Filters().in(Role::getId, ids).isSet(Role::getCode));
        if (!systemRoles.isEmpty()) {
            Role r = systemRoles.get(0);
            throw new BusinessException(
                    op + " is not allowed on system role '" + r.getName() + "' (code=" + r.getCode() + ")");
        }
    }

    /**
     * Reject deleting a role someone still holds. Its assignments would outlive it: a user's roles would
     * list an id that names nothing, which no screen can revoke because none can show it. The roles page
     * already withholds Delete until every assignment is revoked; this holds the API to the same rule.
     * Counted as the system — whether a role is held does not depend on which assignments the caller
     * may read.
     */
    private void guardNoMembers(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        long held = ContextUtils.withoutPermissionChecks(
                () -> modelService.count(USER_ROLE_REL, new Filters().in("roleId", ids)));
        if (held > 0) {
            throw new BusinessException("Revoke all role assignments before deleting.");
        }
    }

    /** Built-in (system-reserved) roles are IMMUTABLE via the API — reject ANY field edit (name,
     *  description, active, code, dynamicFilter, …). Associating users is a separate path
     *  ({@code UserRoleRel}), so this does NOT block assigning members to a built-in role. */
    private void guardSystemMutation(Role patch) {
        if (patch == null || patch.getId() == null) return;
        Role persisted = getById(patch.getId()).orElse(null);
        if (!RoleConstant.isSystemRole(persisted)) return;
        throw new BusinessException(
                "Cannot edit system role '" + persisted.getName() + "' (code=" + persisted.getCode()
                        + "); built-in roles are managed by ops. Only user assignment is allowed.");
    }

    private static Set<Long> idsOf(Role entity) {
        return entity == null || entity.getId() == null ? Set.of() : Set.of(entity.getId());
    }

    private static Set<Long> idsOf(List<Role> entities) {
        if (entities == null) return Set.of();
        Set<Long> ids = new HashSet<>();
        for (Role r : entities) {
            if (r != null && r.getId() != null) ids.add(r.getId());
        }
        return ids;
    }

    /** Evict every holder's cached PermissionInfo for the touched role(s).
     *  Mirrors {@code RoleNavigationServiceImpl}'s publisher — the AFTER_COMMIT
     *  listener fans out {@code evictByRole}. A null tenant context can't be
     *  evicted (the listener keys by tenant), so we warn rather than fail the
     *  write; the snapshot then self-heals at the 1h TTL. */
    private void publishRoleGrantChange(Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        Long tenantId = ContextHolder.getContext() == null ? null
                : ContextHolder.getContext().getTenantId();
        if (tenantId == null) {
            log.warn("Role change publisher — null tenantId; cache eviction will be skipped "
                    + "for roleIds={}. Wrap the caller in ContextHolder.callWith(...).", roleIds);
        }
        for (Long roleId : roleIds) {
            if (roleId != null) events.publishEvent(new RoleNavigationChangedEvent(tenantId, roleId));
        }
    }
}
