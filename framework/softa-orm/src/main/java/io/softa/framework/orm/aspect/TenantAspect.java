package io.softa.framework.orm.aspect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.service.TenantInfoService;

/**
 * Aspect for tenant execution mode.
 */
@Slf4j
@Aspect
@Component
public class TenantAspect {

    private static final int MAX_VIRTUAL_THREADS = 100;

    @Autowired(required = false)
    private TenantInfoService tenantInfoService;

    /**
     * Enable cross-tenant access for the annotated method.
     * Skips tenant isolation and permission check within the method scope.
     */
    @Around("@annotation(io.softa.framework.orm.annotation.CrossTenant)")
    public Object crossTenant(ProceedingJoinPoint joinPoint) throws Throwable {
        Context clonedContext = ContextHolder.cloneContext();
        clonedContext.setCrossTenant(true);
        clonedContext.setSkipPermissionCheck(true);
        return ContextHolder.callWith(clonedContext, joinPoint::proceed);
    }

    /**
     * Execute the annotated method once per active tenant in parallel using virtual threads.
     * Concurrency is capped at {@link #MAX_VIRTUAL_THREADS} to protect the database connection pool.
     */
    @Around("@annotation(io.softa.framework.orm.annotation.PerTenant)")
    public Object perTenant(ProceedingJoinPoint joinPoint) throws Throwable {
        // @PerTenant executes the method N times (once per tenant), return value is meaningless.
        // Only void methods are allowed to avoid silently discarding return values.
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        if (signature.getReturnType() != void.class) {
            throw new IllegalStateException(
                    "@PerTenant can only be applied to void methods, but " + signature.getMethod()
                            + " returns " + signature.getReturnType().getSimpleName());
        }
        if (tenantInfoService == null) {
            throw new IllegalStateException("TenantInfoService is not available. "
                    + "Ensure multi-tenancy is enabled (system.enable-multi-tenancy=true).");
        }
        List<Long> tenantIds = tenantInfoService.getActiveTenantIds();
        String methodName = signature.toShortString();
        Context parentContext = ContextHolder.cloneContext();

        // Submit all tenant tasks and collect futures with their tenantId
        Semaphore semaphore = new Semaphore(MAX_VIRTUAL_THREADS);
        Map<Long, Future<?>> tenantFutures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Long tenantId : tenantIds) {
                tenantFutures.put(tenantId, executor.submit(() -> {
                    semaphore.acquire();
                    try {
                        Context ctx = parentContext.copy();
                        ctx.setTenantId(tenantId);
                        ctx.setSkipPermissionCheck(true);
                        ContextHolder.runWith(ctx, () -> {
                            try {
                                joinPoint.proceed();
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Throwable e) {
                                throw new RuntimeException(e);
                            }
                        });
                        return null;
                    } finally {
                        semaphore.release();
                    }
                }));
            }

            // Wait for ALL tenants to complete, collect failures instead of fail-fast
            List<Long> failedTenantIds = new ArrayList<>();
            Throwable firstCause = null;
            for (Map.Entry<Long, Future<?>> entry : tenantFutures.entrySet()) {
                try {
                    entry.getValue().get();
                } catch (ExecutionException e) {
                    Long failedTenantId = entry.getKey();
                    failedTenantIds.add(failedTenantId);
                    // Unwrap ExecutionException → RuntimeException → original cause
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re && re.getCause() != null) {
                        cause = re.getCause();
                    }
                    if (firstCause == null) {
                        firstCause = cause;
                    }
                    log.error("@PerTenant {} failed for tenantId={}: {}", methodName, failedTenantId, cause.getMessage(), cause);
                }
            }

            if (firstCause != null) {
                throw new RuntimeException(
                        "@PerTenant " + methodName + " failed for tenants " + failedTenantIds, firstCause);
            }
        }
        return null;
    }

}
