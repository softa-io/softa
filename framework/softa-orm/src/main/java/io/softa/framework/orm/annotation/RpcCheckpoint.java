package io.softa.framework.orm.annotation;

import java.lang.annotation.*;

/**
 * RPCCheckpoint annotation, check whether switch the service.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RPCCheckpoint {
}