package io.softa.framework.orm.annotation;

import java.lang.annotation.*;

import io.softa.framework.base.enums.SystemRole;

/**
 * Annotation to specify the system role required to access the method.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireRole {
    SystemRole value();
}