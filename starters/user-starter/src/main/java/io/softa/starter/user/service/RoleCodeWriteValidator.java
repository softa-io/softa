package io.softa.starter.user.service;

import java.util.Objects;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.validation.ModelWriteValidator;
import io.softa.framework.orm.service.validation.WriteContext;

/**
 * {@code Role.code} is written by system paths only — never by a request.
 *
 * <p>The code is what makes a role built-in, and the permission snapshot decides the admin bypass from
 * it alone: whoever can put {@code SUPER_ADMIN} or {@code TENANT_ADMIN} on a role they hold gets that
 * bypass. So the column is closed at the write root, where every path converges — the guarded
 * controllers, the typed {@code RoleService}, the generic model routes, import and flow write nodes —
 * rather than on any one of them:
 * <ul>
 *   <li><b>create</b>: a request may not give a new role a code (admin-created roles have none);</li>
 *   <li><b>update</b>: a request may not change the code of an existing role, in either direction —
 *       setting one on an ordinary role, or clearing or replacing the one a built-in role carries.
 *       Echoing the stored value back (including {@code null} on an ordinary role) is not a change and
 *       passes, so a client that sends the whole row is not refused for it.</li>
 * </ul>
 *
 * <p>Seeding and system maintenance run with {@code skipPermissionCheck} and are let through — that is
 * how the built-in roles are created in the first place. A request never carries that flag.
 */
@Component
@Order(10)
public class RoleCodeWriteValidator implements ModelWriteValidator {

    private static final String ROLE = "Role";
    private static final String CODE = "code";

    @Override
    public boolean supports(String modelName) {
        return ROLE.equals(modelName);
    }

    @Override
    public void validateCreate(WriteContext ctx) {
        if (isSystemPath()) {
            return;
        }
        if (normalize(ctx.row().get(CODE)) != null) {
            ctx.reject(CODE, "Role code is reserved for built-in roles; a role created here must have no code.");
        }
    }

    @Override
    public void validateUpdate(WriteContext ctx) {
        if (isSystemPath() || !ctx.patch().containsKey(CODE)) {
            return;
        }
        Object stored = ctx.originalRow() == null ? null : ctx.originalRow().get(CODE);
        if (!Objects.equals(normalize(ctx.patch().get(CODE)), normalize(stored))) {
            ctx.reject(CODE, "Role code cannot be changed; it is managed by the system.");
        }
    }

    private static boolean isSystemPath() {
        Context context = ContextHolder.getContext();
        return context != null && context.isSkipPermissionCheck();
    }

    /** Blank and null are the same statement: no code. */
    private static String normalize(Object code) {
        if (code == null) {
            return null;
        }
        String text = code.toString();
        return text.isBlank() ? null : text;
    }
}
