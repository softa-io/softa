package io.softa.framework.orm.annotation;

import io.softa.framework.base.enums.SystemRole;

import java.lang.annotation.*;

/**
 * Annotation to specify the system role required to access the method.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireRole {
    SystemRole value();
}