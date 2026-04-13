package io.softa.framework.orm.annotation;

import java.lang.annotation.*;

/**
 * Execute the annotated method once for each active tenant in parallel using virtual threads.
 * <p>
 * The method will be invoked N times (once per active tenant), each with a distinct tenant context.
 * Concurrency is capped at 100 virtual threads to protect the database connection pool.
 * <p>
 * Typically used for scheduled tasks that need to perform per-tenant business logic,
 * such as sending monthly reports or syncing data for each tenant.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PerTenant {
}
