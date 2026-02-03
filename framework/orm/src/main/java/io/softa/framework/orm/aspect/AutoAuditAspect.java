package io.softa.framework.orm.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;

/**
 * Aspect for skip fill audit.
 */
@Aspect
@Component
public class AutoAuditAspect {

    /**
     * Around aspect with SkipAutoAudit annotation.
     * Do not fill audit from the annotated method
     *
     * @param joinPoint Around join point object
     * @return Original method return value
     * @throws Throwable Exception
     */
    @Around("@annotation(io.softa.framework.orm.annotation.SkipAutoAudit)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Context context = ContextHolder.getContext();
        boolean previousValue = context.isSkipAutoAudit();
        try {
            context.setSkipAutoAudit(true);
            return joinPoint.proceed();
        } finally {
            context.setSkipAutoAudit(previousValue);
        }
    }

}
