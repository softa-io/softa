package io.softa.starter.permission.service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.starter.permission.index.EndpointIndex;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The index supplier must not be resolved at construction.
 *
 * <p>{@link EndpointIndex#init()} reads the permission table at {@code @PostConstruct}, which needs
 * {@code ModelManager} already loaded. When {@code PermissionServiceImpl} took the index as a plain
 * constructor argument, Spring built the index the moment the service bean was built — before
 * {@code AppStartup} runs {@code ModelManager.init()} — so the index read an empty catalog and every
 * endpoint answered "Endpoint not registered". The service now holds a {@link Supplier} and resolves
 * it on first use (a request, long after startup).
 *
 * <p>This test fails if someone re-introduces the eager resolve — a `supplier.get()` in the
 * constructor would make the counter non-zero before any call. It is a design guard, not a behaviour
 * check: the symptom only shows at runtime, at startup, and only if the supplier happens to build
 * something expensive or order-dependent, which is exactly the case that bit us.
 */
class EndpointIndexLazyResolveTest {

    @Test
    @DisplayName("constructing the service does not resolve the endpoint index")
    void constructionDoesNotResolveTheIndex() {
        AtomicInteger resolves = new AtomicInteger();
        Supplier<EndpointIndex> counting = () -> {
            resolves.incrementAndGet();
            return null;
        };

        new PermissionServiceImpl(null, null, null, null, null, counting);

        assertEquals(0, resolves.get(),
                "the service resolved the EndpointIndex supplier during construction — that is the "
                        + "eager path that built the index before ModelManager was loaded and left it empty");
    }
}
