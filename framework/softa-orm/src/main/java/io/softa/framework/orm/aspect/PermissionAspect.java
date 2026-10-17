package io.softa.framework.orm.aspect;

import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.annotation.RequireRole;
import io.softa.framework.orm.annotation.SwitchUser;

/**
 * Aspect for permission check.
 */
@Slf4j
@Aspect
@Component
public class PermissionAspect {

    /**
     * Around aspect with SkipPermissionCheck annotation.
     * Do not check permission from the annotated method, but the context user still keeps the current user.
     *
     * <h3>Unbound-context short-circuit</h3>
     * {@code ContextHolder.getContext()} returns a fresh default Context when no
     * {@link ScopedValue} binding exists on the current thread
     * ({@link ContextHolder#existContext()} returns {@code false}), so mutating it
     * here would be discarded — the next {@code getContext()} downstream hands back
     * another new instance.
     *
     * <p>That costs nothing, because the outcome does not depend on the flag in that
     * case: {@code PermissionServiceImpl.shouldBypass()} already returns {@code true}
     * on {@code !existContext()}, so checks are skipped either way. The annotation is
     * simply redundant on such threads, not defeated — we return early rather than
     * mutate a throwaway.
     *
     * <p>This used to log a WARN. It was removed: an unbound context is the norm on
     * scheduler / bootstrap / MQ threads, and the annotation sits on the
     * {@code JdbcServiceImpl} read-write methods every ORM call funnels through, so
     * the warning fired continuously (message-starter's outbox polls every 500ms) and
     * reported something with no consequence. The failure it was written for — a
     * caller who expected a bound context and lost it — is not observable from here:
     * where the flag actually decides the outcome, the context IS bound and this
     * branch is not taken.
     * @param joinPoint Around join point object
     * @return Original method return value
     * @throws Throwable Exception
     */
    @Around("@annotation(io.softa.framework.orm.annotation.SkipPermissionCheck)")
    public Object skipPermissionCheck(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!ContextHolder.existContext()) {
            // Nothing to do, and nothing worth reporting. The flag would land on the throwaway
            // Context getContext() hands back when unbound, so it never reaches
            // PermissionServiceImpl.shouldBypass() — which already returns true on
            // !existContext(). The outcome is identical either way, so the annotation is merely
            // redundant here, not defeated.
            return joinPoint.proceed();
        }
        Context context = ContextHolder.getContext();
        boolean previousValue = context.isSkipPermissionCheck();
        try {
            context.setSkipPermissionCheck(true);
            // Remembered for CallerPermissions.asCaller: the nested writes made inside are the caller's.
            return ScopedValue.where(CallerPermissions.CALLER_SKIPPED, previousValue).call(joinPoint::proceed);
        } finally {
            context.setSkipPermissionCheck(previousValue);
        }
    }

    /**
     * {@link RequireRole} annotation aspect — verify the caller holds the
     * required system role BEFORE running the annotated (privileged) method.
     *
     * <h3>Fail-closed contract</h3>
     * The caller's role codes are read from the framework-layer
     * {@link Context#getRoleCodes()}. The consuming application's request
     * pipeline is responsible for populating that set (the framework stays
     * decoupled from any concrete permission model — this field is
     * the SPI). When the set is absent or lacks the required role code we
     * DENY: a {@code null} set means either the app wired no role provider or
     * the endpoint was whitelisted upstream (public / authenticated-bypass) —
     * both must not grant a system-role-gated method.
     *
     * <p>{@code skipPermissionCheck} is enabled ONLY after the role is
     * verified — never before — so the bypass can't leak to an unauthorized
     * caller (previously the advice skipped enforcement unconditionally
     * without ever checking the role).
     */
    @Around("@annotation(requireRole)")
    public Object requireRole(ProceedingJoinPoint joinPoint, RequireRole requireRole) throws Throwable {
        Context context = ContextHolder.getContext();
        Set<String> roleCodes = context == null ? null : context.getRoleCodes();
        String requiredCode = requireRole.value().getCode();
        if (roleCodes == null || !roleCodes.contains(requiredCode)) {
            throw new PermissionException("Requires system role: " + requireRole.value().getName());
        }
        // Role verified — this is a system-level operation, so downstream
        // scope / SFS / write guards are intentionally bypassed.
        boolean previousIgnoreValue = context.isSkipPermissionCheck();
        try {
            context.setSkipPermissionCheck(true);
            return joinPoint.proceed();
        } finally {
            context.setSkipPermissionCheck(previousIgnoreValue);
        }
    }

    /**
     * Switch current user to the specified system level user, in order to access the system resources.
     */
    @Around("@annotation(switchUser)")
    public Object switchUser(ProceedingJoinPoint joinPoint, SwitchUser switchUser) throws Throwable {
        Context clonedContext = ContextHolder.cloneContext();
        String userName = switchUser.alias().isBlank() ? switchUser.value().getName() : switchUser.alias();
        clonedContext.setName(userName);
        // Skip permission check for system level users.
        clonedContext.setSkipPermissionCheck(true);
        // Switch context to the cloned context.
        return ContextHolder.callWith(clonedContext, joinPoint::proceed);
    }

}
