package io.softa.app.metadata;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.scanner.CatalogBootCheck;

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
}
