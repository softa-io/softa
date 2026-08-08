package io.softa.starter.permission.aspect;

import java.lang.reflect.Method;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;

import io.softa.starter.permission.annotation.RequireModel;
import io.softa.starter.permission.annotation.RequireModels;
import lombok.RequiredArgsConstructor;

/**
 * Fail-loud at startup: resolve every {@code @RequireModel} declaration in
 * the application's controllers so a typo'd parameter name, a non-inferable
 * model, or a non-{@code Filters} filter parameter aborts the boot instead of
 * surfacing as a request-time 500 — the same posture {@code EndpointIndex}
 * takes for malformed endpoint strings.
 *
 * <p>Runs after all singletons exist ({@link SmartInitializingSingleton}), and
 * uses pure reflection only (no model-registry lookups), so it has no ordering
 * dependency on metadata scanning.
 */
@Component
@RequiredArgsConstructor
public class RequireModelStartupValidator implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;

    @Override
    public void afterSingletonsInstantiated() {
        for (Object bean : applicationContext.getBeansWithAnnotation(Controller.class).values()) {
            Class<?> target = AopUtils.getTargetClass(bean);
            for (Method method : target.getMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, RequireModel.class)
                        || AnnotatedElementUtils.hasAnnotation(method, RequireModels.class)) {
                    // Throws IllegalStateException with the concrete fix on any mismatch.
                    RequireModelAspect.resolve(method);
                }
            }
        }
    }
}
