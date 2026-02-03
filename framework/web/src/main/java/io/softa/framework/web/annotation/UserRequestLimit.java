package io.softa.framework.web.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotation for user request limiting in sensitive API methods.
 * Commonly used to prevent abuse or excessive usage in user level or tenant level.
 * General request limiting should be done at the gateway level.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UserRequestLimit {

    /**
     * Limit type, default is "userId", can also be "tenantId"
     */
    String type() default "userId";

    /**
     * Limit count, default is 100
     */
    int limit() default 100;

    /**
     * Time window in seconds, default is 60 seconds
     */
    long time() default 60;
}
