package io.softa.framework.orm.aspect;

import java.util.Set;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.PermissionInfo;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.annotation.RequireRole;
import io.softa.framework.orm.annotation.SwitchUser;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

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
     * <h3>ScopedValue-binding requirement (Known-Issues Lat2)</h3>
     * {@code ContextHolder.getContext()} returns a fresh default Context
     * when no {@link ScopedValue} binding exists on the current thread
     * ({@link ContextHolder#existContext()} returns {@code false}). Our
     * {@code setSkipPermissionCheck(true)} then mutates that transient
     * instance — the mutation is discarded the moment this aspect returns,
     * because the outer caller sees a new default Context on its next
     * {@code getContext()} call. Net effect: {@code @SkipPermissionCheck}
     * is a silent no-op, downstream {@code PermissionServiceImpl} still
     * enforces scope / SFS / write guards. This is a legit failure mode
     * for callers who forgot to wrap in
     * {@code ContextHolder.runWith(...) / callWith(...)}, and used to be
     * silent.
     *
     * <p>Log a WARN so ops can trace it — but do not throw. Legit code
     * paths at framework boot / lifecycle events may hit this before the
     * request-scoped context is bound; a throw would break them, whereas
     * the intended semantics (skip check) simply falls back to "check as
     * usual" which is safe.
     * @param joinPoint Around join point object
     * @return Original method return value
     * @throws Throwable Exception
     */
    @Around("@annotation(io.softa.framework.orm.annotation.SkipPermissionCheck)")
    public Object skipPermissionCheck(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!ContextHolder.existContext()) {
            log.warn("@SkipPermissionCheck on {} runs outside a bound ContextHolder "
                    + "ScopedValue — mutation is discarded and the annotation is a no-op. "
                    + "Wrap the caller in ContextHolder.runWith(bootstrapCtx, ...) or "
                    + "ContextHolder.callWith(...).",
                    joinPoint.getSignature().toShortString());
        }
        Context context = ContextHolder.getContext();
        boolean previousValue = context.isSkipPermissionCheck();
        try {
            context.setSkipPermissionCheck(true);
            return joinPoint.proceed();
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
     * {@link Context#getPermissionInfo()}. The consuming application's request
     * pipeline is responsible for populating that set (the framework stays
     * decoupled from any concrete Role / PermissionInfo model — this field is
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
        PermissionInfo permissionInfo = context == null ? null : context.getPermissionInfo();
        Set<String> roleCodes = permissionInfo == null ? null : permissionInfo.getRoleCodes();
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
