package io.softa.app.metadata;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.meta.TemplateVariableSetRegistry;
import io.softa.framework.orm.meta.TemplateVariableSetScanner;
import io.softa.starter.metadata.scanner.CatalogBootCheck;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Every softa model, put through the checks an application makes when it loads its catalog at
 * boot ({@link CatalogBootCheck}).
 *
 * <p>Those checks read the whole catalog, so a mistake can pass each module's own tests and stop
 * every application on the release instead — 3.0.1 did, over {@code InboxNotification}. demo-app's
 * test classpath holds every softa module that declares models, and the release workflow runs this
 * test ({@code mvn verify}) before anything is published.
 *
 * <p>With multi-tenancy on, as demo-app and the multi-tenant applications run: it adds checks and
 * removes none.
 */
class SoftaCatalogBootCheckTest {

    @Test
    void everySoftaModelPassesTheBootChecks() {
        CatalogBootCheck.validate(List.of("io.softa"), true);
    }

    /**
     * Every variable set ({@code @TemplateVariableSet}) softa declares, registered the way an application
     * registers them at boot. A name declared twice — by two starters that never meet in each
     * other's tests — or a set that cannot be described stops every application there, so it is
     * caught here first. An application adds its own sets and repeats this check over its packages.
     */
    @Test
    void everySoftaTemplateVariableSetRegisters() {
        Set<Class<?>> declared = TemplateVariableSetScanner.scan(Set.of("io.softa"));
        try {
            TemplateVariableSetRegistry.register(declared);
            assertFalse(TemplateVariableSetRegistry.names().isEmpty(), "softa declares template variables");
        } finally {
            TemplateVariableSetRegistry.register(Set.of());
        }
    }
}
