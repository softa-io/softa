package io.softa.starter.user.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextHolder;
import static io.softa.framework.base.context.ContextUtils.inSystemContext;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.event.RoleGrantChangedEvent;

/**
 * A built-in role's access is fixed: a role carrying a {@code code} cannot have its definition or its
 * grants written through the API. Assigning and unassigning users is the one change that stays open.
 *
 * <p>The grants are three ordinary models — {@code RoleNavigation} (which pages), {@code RoleDataScope}
 * (which rows), {@code RoleSensitiveFieldSet} (which fields unmasked) — plus {@code Role} itself. They
 * are guarded at the HTTP surface by {@link io.softa.starter.user.controller.SystemRoleGuardedController},
 * which declares every write verb so none of them can slip through the generic {@code /{modelName}}
 * route unchecked. This class holds the decision; that class decides where it is asked.
 *
 * <p>What was reachable before this existed, on a live tenant: {@code POST /RoleDataScope/updateOne}
 * naming the EMPLOYEE role's row on the {@code Employee} model and setting {@code scopeType: ALL} —
 * one call, and every employee in that tenant reads every colleague's record. {@code SUPER_ADMIN} and
 * {@code TENANT_ADMIN} happen to be immune (their access is computed at runtime and their static rows
 * are ignored), which is exactly why the hole was easy to miss: the two roles an operator would think
 * to test are the two that do not react.
 *
 * <h3>What still passes</h3>
 * <ul>
 *   <li><b>Membership.</b> {@code UserRoleRel} has no guarded controller — adding and removing users is
 *       the point of a built-in role, not a modification of it.</li>
 *   <li><b>Seeding and system maintenance.</b> Anything running with {@code skipPermissionCheck} is let
 *       through: that is what pre-data loading sets on both its tenant and system paths, and what the
 *       entitlement downgrade cleanup runs under when it strips over-plan grants. A request arriving
 *       from a user never carries it — not even a super admin's, whose bypass is expressed through
 *       {@code PermissionInfo.isAdmin}, not through this flag.</li>
 *   <li><b>Service-layer callers.</b> This guard sits at the HTTP surface, so framework-internal writes
 *       (a cascade, a seeder, the entitlement cleanup) are unaffected — they were never the threat.</li>
 * </ul>
 */
@Slf4j
@Component
public class SystemRoleWriteGuard {

    private static final String ROLE = "Role";
    private static final String ID = "id";
    private static final String CODE = "code";

    /** Guarded model → the field naming the role the row belongs to. */
    private static final Map<String, String> GUARDED = Map.of(
            ROLE, ID,
            "RoleNavigation", "roleId",
            "RoleDataScope", "roleId",
            "RoleSensitiveFieldSet", "roleId");

    private final ModelService<?> modelService;
    private final ApplicationEventPublisher events;

    public SystemRoleWriteGuard(ModelService<?> modelService, ApplicationEventPublisher events) {
        this.modelService = modelService;
        this.events = events;
    }

    /**
     * Clear the cached access of every holder of these roles, after a generic write changed them. The
     * typed services announce their own writes; the generic endpoints wrote past them, and holders kept
     * the old access until the cache expired.
     */
    public void announce(Collection<?> roleIds) {
        if (roleIds == null || roleIds.isEmpty() || ContextHolder.getContext() == null) {
            return;
        }
        Long tenantId = ContextHolder.getContext().getTenantId();
        for (Object roleId : roleIds) {
            Long id = roleId instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(roleId));
            events.publishEvent(new RoleGrantChangedEvent(tenantId, id));
        }
    }

    /**
     * A create names its role in the payload — except on {@code Role}, where the row has no id yet and
     * what makes it built-in is the {@code code} it is asking for. Reserving the column here matters
     * because everything else in this class keys off it: a caller that could mint its own coded role
     * could then declare any role untouchable.
     */
    public Set<Object> guardCreate(String modelName, List<Map<String, Object>> rows) {
        String roleField = roleFieldOrSkip(modelName);
        if (roleField == null) {
            return Set.of();
        }
        refuseOutsideTheWizard(modelName);
        if (ROLE.equals(modelName)) {
            for (Map<String, Object> row : rows) {
                Object code = row == null ? null : row.get(CODE);
                if (code != null && !code.toString().isBlank()) {
                    throw new BusinessException(
                            "Role code is reserved for built-in roles; an admin-created role must have code=null.");
                }
            }
            return Set.of();
        }
        Set<Object> roles = valuesOf(rows, roleField);
        assertNoSystemRole(roles);
        return roles;
    }

    /**
     * An update is checked on both sides — the role named in the payload and the role the stored row
     * currently belongs to. Only checking the payload would let a grant be dragged off a built-in role;
     * only checking the stored row would let one be dragged onto it.
     */
    public Set<Object> guardUpdate(String modelName, List<Map<String, Object>> rows) {
        String roleField = roleFieldOrSkip(modelName);
        if (roleField == null) {
            return Set.of();
        }
        refuseOutsideTheWizard(modelName);
        Set<Object> ids = new HashSet<>(resolveByIds(modelName, roleField, valuesOf(rows, ID)));
        ids.addAll(valuesOf(rows, roleField));
        assertNoSystemRole(ids);
        return ids;
    }

    /** Same both-sides rule as {@link #guardUpdate}, with the rows chosen by a filter. */
    public Set<Object> guardUpdateByFilter(String modelName, Filters filters, Map<String, Object> values) {
        String roleField = roleFieldOrSkip(modelName);
        if (roleField == null) {
            return Set.of();
        }
        refuseOutsideTheWizard(modelName);
        Set<Object> ids = new HashSet<>(resolveByFilters(modelName, roleField, filters));
        ids.addAll(valuesOf(values == null ? List.of() : List.of(values), roleField));
        assertNoSystemRole(ids);
        return ids;
    }

    /** A copy writes a role's grants as a create does; the same rule, then the built-in check by id. */
    public Set<Object> guardCopy(String modelName, Collection<?> rowIds) {
        String roleField = roleFieldOrSkip(modelName);
        if (roleField == null) {
            return Set.of();
        }
        refuseOutsideTheWizard(modelName);
        guardByIds(modelName, rowIds);
        // A copied grant row lands on the role it was copied from; a copied role has no holders yet.
        return ROLE.equals(modelName) ? Set.of() : resolveByIds(modelName, roleField, new ArrayList<>(rowIds));
    }

    /**
     * Grants are written through the role wizard, which holds them to the caller's own and publishes the
     * change, or by an administrator. The generic writes held to neither: a caller who may update roles
     * could write any grant row onto a role they hold, or a member filter the bind checks never saw, and
     * every holder kept the old access until the cache expired.
     */
    private static void refuseOutsideTheWizard(String modelName) {
        // Outside any request — a seeder, a test — is system work, as the permission layer reads it.
        if (ContextHolder.existContext() && RoleGrantCeiling.applies()) {
            throw new PermissionException("{0} is changed through the role wizard; the generic write endpoints "
                    + "are for administrators.", modelName);
        }
    }

    /** delete / copy — the rows are named by id, and the role behind them is read back. */
    public void guardByIds(String modelName, Collection<?> rowIds) {
        String roleField = roleFieldOrSkip(modelName);
        if (roleField == null) {
            return;
        }
        assertNoSystemRole(resolveByIds(modelName, roleField, new ArrayList<>(rowIds)));
    }

    /**
     * The role field for a guarded model, or null when there is nothing to guard — either the model is
     * not one of the four, or the caller is a system path (see the class javadoc on what still passes).
     */
    private String roleFieldOrSkip(String modelName) {
        if (ContextHolder.getContext() != null && ContextHolder.getContext().isSkipPermissionCheck()) {
            return null;
        }
        return GUARDED.get(modelName);
    }

    /** The role behind each of these rows. On {@code Role} the ids ARE the role ids — no read needed. */
    private Set<Object> resolveByIds(String modelName, String roleField, Collection<Object> rowIds) {
        if (rowIds.isEmpty()) {
            return Set.of();
        }
        if (ROLE.equals(modelName)) {
            return new HashSet<>(rowIds);
        }
        return resolveByFilters(modelName, roleField, new Filters().in(ID, rowIds));
    }

    /**
     * Read the role ids of the rows a filter selects.
     *
     * <p>System context, so the guard sees the rows rather than the caller's view of them: a scoped read
     * that returns nothing would look exactly like "no built-in role involved" and wave the write past.
     */
    private Set<Object> resolveByFilters(String modelName, String roleField, Filters filters) {
        List<Map<String, Object>> rows = inSystemContext(() ->
                modelService.searchList(modelName, new FlexQuery(List.of(roleField), filters)));
        return valuesOf(rows, roleField);
    }

    /** Refuse the write if any of these roles is built-in, naming the one that stopped it. */
    private void assertNoSystemRole(Set<Object> roleIds) {
        if (roleIds.isEmpty()) {
            return;
        }
        List<Map<String, Object>> roles = inSystemContext(() -> modelService.searchList(
                ROLE, new FlexQuery(List.of(ID, CODE, "name"), new Filters().in(ID, roleIds))));
        for (Map<String, Object> role : roles) {
            Object code = role.get(CODE);
            if (code != null && !code.toString().isBlank()) {
                throw new BusinessException(
                        "Cannot modify built-in role '" + role.get("name") + "' (code=" + code
                                + ") or its access; built-in roles are managed by ops. "
                                + "Only assigning and unassigning users is allowed.");
            }
        }
    }

    private static Set<Object> valuesOf(List<Map<String, Object>> rows, String field) {
        Set<Object> values = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object value = row == null ? null : row.get(field);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }
}
