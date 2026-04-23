package io.softa.framework.web.signature;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method (or an entire controller class) as requiring
 * Ed25519 request signature verification. When placed on a class, every
 * handler method in that class inherits the requirement unless overridden.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireSignature {
}
