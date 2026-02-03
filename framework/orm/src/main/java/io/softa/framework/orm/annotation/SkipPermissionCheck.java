package io.softa.framework.orm.annotation;

import java.lang.annotation.*;

/**
 * Skip permission check for the method.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SkipPermissionCheck {
}