package io.softa.framework.orm.annotation;

import java.lang.annotation.*;

/**
 * Skip tenant isolation for the annotated method.
 * The ORM will not apply tenant_id filtering on reads, and will not auto-fill tenantId on writes.
 * <p>
 * Typically used for system-level operations such as scheduled tasks or data migration
 * that need to read data across all tenants.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CrossTenant {
}
