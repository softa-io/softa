package io.softa.starter.user.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.enums.BuiltinRole;
import static io.softa.framework.base.context.ContextUtils.inSystemContext;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;

/**
 * Who may put a user into a role.
 *
 * <p>Holding {@code SUPER_ADMIN} or {@code TENANT_ADMIN} is a bypass, not a set of grants, so binding
 * one is the most powerful write a request can make. Checked at the write root, where the typed
 * service, the generic model routes, bulk assignment and import all converge:
 * <ul>
 *   <li>{@code SUPER_ADMIN} is never bound by a request — it is managed by ops;</li>
 *   <li>{@code TENANT_ADMIN} is bound only by a caller who already holds a built-in admin role. A role
 *       manager without that bypass may otherwise assign it to anyone, themselves included, and step
 *       from "manages roles" to "bypasses every check in the tenant".</li>
 *   <li>any other role is bound by a caller without a built-in admin role only when it is within that
 *       caller's own grants ({@link RoleGrantCeiling}) — otherwise assigning an existing, wider role to
 *       oneself would do what the ceiling refuses on the role definition.</li>
 * </ul>
 * A bind is a create naming the role, or an update that moves an existing row onto the role or onto
 * another user — the second is the same grant handed to someone else.
 *
 * <p>System paths ({@code skipPermissionCheck}) pass: provisioning binds the first tenant admin that
 * way, and seeding binds whatever the seed lists.
 */
@Component
@Order(20)
public class RoleBindValidator implements ModelWriteValidator {

    private static final String USER_ROLE_REL = "UserRoleRel";
    private static final String ROLE = "Role";
    private static final String ROLE_ID = "roleId";
    private static final String USER_ID = "userId";
    private static final String CODE = "code";

    private final ModelService<?> modelService;
    private final RoleGrantCeiling ceiling;

    public RoleBindValidator(ModelService<?> modelService, RoleGrantCeiling ceiling) {
        this.modelService = modelService;
        this.ceiling = ceiling;
    }

    @Override
    public boolean supports(String modelName) {
        return USER_ROLE_REL.equals(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        if (!isSystemPath()) {
            check(ctx, ctx.row().get(ROLE_ID));
        }
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (isSystemPath() || !(changes(ctx, ROLE_ID) || changes(ctx, USER_ID))) {
            return;
        }
        check(ctx, ctx.row().get(ROLE_ID));
    }

    private void check(WriteContext ctx, Object roleId) {
        String code = roleCode(ctx, roleId);
        if (BuiltinRole.SUPER_ADMIN.getCode().equals(code)) {
            ctx.reject(ROLE_ID, "The Super Admin role cannot be assigned here; it is managed by the platform.");
        } else if (BuiltinRole.TENANT_ADMIN.getCode().equals(code) && !callerIsAdmin()) {
            ctx.reject(ROLE_ID, "Only an administrator can assign the Tenant Admin role.");
        } else if (roleId != null && !callerIsAdmin()) {
            ceilingViolation(ctx, roleId).ifPresent(reason ->
                    ctx.reject(ROLE_ID, "You can only assign a role whose access you hold yourself: " + reason));
        }
    }

    /** Asked once per role per write: a bulk assignment names many users and few roles. */
    @SuppressWarnings("unchecked")
    private Optional<String> ceilingViolation(WriteContext ctx, Object roleId) {
        String key = "ceiling:" + asText(roleId);
        return (Optional<String>) ctx.scratch().computeIfAbsent(key,
                k -> ceiling.violation(Long.valueOf(asText(roleId))));
    }

    /** Whether the patch writes this field to a value other than the stored one. */
    private static boolean changes(WriteContext ctx, String field) {
        if (!ctx.patch().containsKey(field)) {
            return false;
        }
        Object stored = ctx.originalRow() == null ? null : ctx.originalRow().get(field);
        return !Objects.equals(asText(ctx.patch().get(field)), asText(stored));
    }

    /**
     * The role's code, read without the caller's row scope — a role the caller cannot see must not
     * look like "no admin role involved". Cached per write, since a bulk assignment names few roles.
     */
    private String roleCode(WriteContext ctx, Object roleId) {
        if (roleId == null) {
            return null;
        }
        String key = asText(roleId);
        Map<String, Object> scratch = ctx.scratch();
        if (!scratch.containsKey(key)) {
            List<Map<String, Object>> roles = inSystemContext(() -> modelService.searchList(
                    ROLE, new FlexQuery(List.of("id", CODE), new Filters().eq("id", roleId))));
            scratch.put(key, roles.isEmpty() ? null : roles.getFirst().get(CODE));
        }
        Object code = scratch.get(key);
        return code == null ? null : code.toString();
    }

    private static boolean callerIsAdmin() {
        Set<String> codes = ContextHolder.getContext() == null ? null : ContextHolder.getContext().getRoleCodes();
        return BuiltinRole.anyHeldBy(codes, BuiltinRole.SUPER_ADMIN, BuiltinRole.TENANT_ADMIN, BuiltinRole.CONSULTANT);
    }

    private static boolean isSystemPath() {
        Context context = ContextHolder.getContext();
        return context != null && context.isSkipPermissionCheck();
    }

    /** Ids arrive as numbers or strings depending on the client; compare them as text. */
    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }
}
