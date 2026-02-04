package io.softa.framework.orm.aspect;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.annotation.SwitchUser;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Aspect for permission check.
 */
@Aspect
@Component
public class PermissionAspect {

    /**
     * Around aspect with SkipPermissionCheck annotation.
     * Do not check permission from the annotated method, but the context user still keeps the current user.
     * @param joinPoint Around join point object
     * @return Original method return value
     * @throws Throwable Exception
     */
    @Around("@annotation(io.softa.framework.orm.annotation.SkipPermissionCheck)")
    public Object skipPermissionCheck(ProceedingJoinPoint joinPoint) throws Throwable {
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
     * RequireRole annotation aspect.
     * Check if the current user has the specified role permission.
     */
    @Around("@annotation(io.softa.framework.orm.annotation.RequireRole)")
    public Object requireRole(ProceedingJoinPoint joinPoint) throws Throwable {
        Context context = ContextHolder.getContext();
        // TODO: User role check, if the user does not have the specified role, throw an exception.
        // Skip permission check after role check.
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
