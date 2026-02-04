package io.softa.framework.orm.aspect;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;


/**
 * Debug annotation aspect.
 */
@Aspect
@Component
public class DebugAspect {

    @Around("@annotation(io.softa.framework.orm.annotation.Debug)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Context context = ContextHolder.getContext();
        boolean previousValue = context.isDebug();
        try {
            context.setDebug(true);
            return joinPoint.proceed();
        } finally {
            context.setDebug(previousValue);
        }
    }

}
