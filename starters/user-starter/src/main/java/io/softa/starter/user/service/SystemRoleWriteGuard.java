package io.softa.starter.user.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextHolder;
import static io.softa.framework.base.context.ContextUtils.inSystemContext;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.user.constant.RoleConstant;

/**
 * What may be written to a role and its grants through the API.
 *
 * <p>The grants are three ordinary models — {@code RoleNavigation} (which pages), {@code RoleDataScope}
 * (which rows), {@code RoleSensitiveFieldSet} (which fields unmasked) — plus {@code Role} itself. They
 * are guarded at the HTTP surface by {@link io.softa.starter.user.controller.SystemRoleGuardedController},
 * which declares every write verb so none of them can slip through the generic {@code /{modelName}}
 * route unchecked. This class holds the decision; that class decides where it is asked.
 *
 * <h3>The rules</h3>
 * <ul>
 *   <li><b>A role's {@code code} never changes.</b> It is what makes a role built-in: a caller that could
 *       set one could make any role undeletable, and one that could clear one could delete a built-in
 *       role. A create may not ask for a code; an update may only repeat the stored one.</li>
 *   <li><b>A built-in role cannot be deleted or copied</b> — a copy would carry the reserved code.</li>
 *   <li><b>The administrator roles accept no edit</b> ({@link RoleConstant#isAdminRole}): neither the
 *       role nor its grants. Their access is computed at runtime and their grant rows are never read, so
 *       an edit would show a change that does nothing — and disabling one could leave nobody able to
 *       administer the tenant.</li>
 *   <li><b>Every other built-in role is the tenant's to reshape</b> — its name, status, membership rule
 *       and all three kinds of grant. What stands between a user and that is the endpoint permission on
 *       role management, the same as for a role the tenant created itself.</li>
 * </ul>
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
    private static final String NAME = "name";

    /** Guarded model → the field naming the role the row belongs to. */
    private static final Map<String, String> GUARDED = Map.of(
            ROLE, ID,
            "RoleNavigation", "roleId",
            "RoleDataScope", "roleId",
            "RoleSensitiveFieldSet", "roleId");

    private final ModelService<?> modelService;

    public SystemRoleWriteGuard(ModelService<?> modelService) {
        this.modelService = modelService;
    }

    /**
     * A create names its role in the payload — except on {@code Role}, where the row has no id yet and
     * what would make it built-in is the {@code code} it is asking for.
     */
    public void guardCreate(String modelName, List<Map<String, Object>> rows) {
        String roleField = roleFieldOrSkip(modelName);
        if (roleField == null) {
            return;
        }
        if (ROLE.equals(modelName)) {
            for (Map<String, Object> row : rows) {
                if (isSet(row == null ? null : row.get(CODE))) {
                    throw new BusinessException(
                            "Role code is reserved for built-in roles; an admin-created role must have code=null.");
                }
            }
            return;
        }
        assertNoAdminRole(valuesOf(rows, roleField));
    }

    /**
     * An update of a grant is checked on both sides — the role named in the payload and the role the
     * stored row currently belongs to. Only checking the payload would let a grant be dragged off an
     * administrator role; only checking the stored row would let one be dragged onto it. An update of a
     * {@code Role} is checked against the stored role: never an administrator role, never a new code.
     */
    public void guardUpdate(String modelName, List<Map<String, Object>> rows) {
        String roleField = roleFieldOrSkip(modelName);
        if (roleField == null) {
            return;
        }
        if (ROLE.equals(modelName)) {
            Map<String, Map<String, Object>> stored = rolesById(valuesOf(rows, ID));
            for (Map<String, Object> row : rows) {
                if (row == null) {
                    continue;
                }
                Map<String, Object> role = row.get(ID) == null ? null : stored.get(row.get(ID).toString());
                if (role == null) {
                    continue;
                }
                assertNotAdminRole(role);
                if (row.containsKey(CODE)) {
                    assertSameCode(role, row.get(CODE));
                }
            }
            return;
        }
        Set<Object> ids = new HashSet<>(resolveByIds(modelName, roleField, valuesOf(rows, ID)));
        ids.addAll(valuesOf(rows, roleField));
        assertNoAdminRole(ids);
    }

    /** Same rules as {@link #guardUpdate}, with the rows chosen by a filter. */
    public void guardUpdateByFilter(String modelName, Filters filters, Map<String, Object> values) {
        String roleField = roleFieldOrSkip(modelName);
        if (roleField == null) {
            return;
        }
        Map<String, Object> patch = values == null ? Map.of() : values;
        if (ROLE.equals(modelName)) {
            for (Map<String, Object> role : rolesById(resolveByFilters(ROLE, ID, filters)).values()) {
                assertNotAdminRole(role);
                if (patch.containsKey(CODE)) {
                    assertSameCode(role, patch.get(CODE));
                }
            }
            return;
        }
        Set<Object> ids = new HashSet<>(resolveByFilters(modelName, roleField, filters));
        ids.addAll(valuesOf(List.of(patch), roleField));
        assertNoAdminRole(ids);
    }

    /**
     * delete / copy — the rows are named by id, and the role behind them is read back. A built-in role
     * itself can be neither deleted nor copied; a grant of one can, unless it is an administrator role's.
     */
    public void guardByIds(String modelName, Collection<?> rowIds) {
        String roleField = roleFieldOrSkip(modelName);
        if (roleField == null) {
            return;
        }
        Set<Object> roleIds = resolveByIds(modelName, roleField, new ArrayList<>(rowIds));
        if (ROLE.equals(modelName)) {
            assertNoBuiltInRole(roleIds);
        } else {
            assertNoAdminRole(roleIds);
        }
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
     * that returns nothing would look exactly like "no guarded role involved" and wave the write past.
     */
    private Set<Object> resolveByFilters(String modelName, String roleField, Filters filters) {
        List<Map<String, Object>> rows = inSystemContext(() ->
                modelService.searchList(modelName, new FlexQuery(List.of(roleField), filters)));
        return valuesOf(rows, roleField);
    }

    /**
     * The stored roles, keyed by the string form of their id — a payload may carry an id as a number or
     * as a string, and a lookup that misses would read as "no role here" and let the write through.
     * Read in system context for the same reason as above.
     */
    private Map<String, Map<String, Object>> rolesById(Set<Object> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> roles = inSystemContext(() -> modelService.searchList(
                ROLE, new FlexQuery(List.of(ID, CODE, NAME), new Filters().in(ID, roleIds))));
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> role : roles) {
            byId.put(String.valueOf(role.get(ID)), role);
        }
        return byId;
    }

    /** Refuse the write if any of these roles is an administrator role, naming the one that stopped it. */
    private void assertNoAdminRole(Set<Object> roleIds) {
        rolesById(roleIds).values().forEach(this::assertNotAdminRole);
    }

    /** Refuse a delete / copy if any of these roles is built-in. */
    private void assertNoBuiltInRole(Set<Object> roleIds) {
        for (Map<String, Object> role : rolesById(roleIds).values()) {
            if (isSet(role.get(CODE))) {
                throw new BusinessException(
                        "Cannot delete or copy built-in role '" + role.get(NAME) + "' (code=" + role.get(CODE) + ").");
            }
        }
    }

    private void assertNotAdminRole(Map<String, Object> role) {
        Object code = role.get(CODE);
        if (code != null && isAdminCode(code.toString())) {
            throw new BusinessException(
                    "Cannot modify administrator role '" + role.get(NAME) + "' (code=" + code
                            + ") or its access; its access is computed, not configured. "
                            + "Only assigning and unassigning users is allowed.");
        }
    }

    /** An update may repeat the stored code, and nothing else — not a new one, not a cleared one. */
    private static void assertSameCode(Map<String, Object> role, Object requested) {
        Object stored = role.get(CODE);
        String storedCode = isSet(stored) ? stored.toString() : null;
        String requestedCode = isSet(requested) ? requested.toString() : null;
        if (!Objects.equals(storedCode, requestedCode)) {
            throw new BusinessException(storedCode == null
                    ? "Role code is reserved for built-in roles; it cannot be set on role '" + role.get(NAME) + "'."
                    : "The code of built-in role '" + role.get(NAME) + "' (code=" + storedCode + ") cannot be changed.");
        }
    }

    private static boolean isAdminCode(String code) {
        return RoleConstant.CODE_SUPER_ADMIN.equals(code) || RoleConstant.CODE_TENANT_ADMIN.equals(code);
    }

    private static boolean isSet(Object value) {
        return value != null && !value.toString().isBlank();
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
