package io.softa.starter.permission.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container for the repeatable {@link RequireModel}. Never used directly —
 * stack multiple {@code @RequireModel} annotations instead.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireModels {

    RequireModel[] value();
}
