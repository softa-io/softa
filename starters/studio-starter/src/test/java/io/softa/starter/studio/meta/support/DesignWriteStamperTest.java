package io.softa.starter.studio.meta.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.service.ModelService;

/**
 * {@link DesignWriteStamper} stamps the per-env identity on a design write — id minting (when
 * absent), default-env resolution (lowest active env), client-supplied-env validation, and the
 * set-once freeze of {@code envId} on update. (The id-minting branch uses the distributed CosID
 * generator and is exercised at runtime.)
 */
@SuppressWarnings("unchecked")
class DesignWriteStamperTest {

    private static final long APP = 7L;

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("create with an explicit id keeps it and keeps a client-supplied envId that belongs to the app")
    void createKeepsExplicitIdAndEnv() {
        ModelService<Long> ms = mock(ModelService.class);
        // env 42 belongs to the app → accepted.
        when(ms.searchList(eq("DesignAppEnv"), any(FlexQuery.class))).thenReturn(List.of(row("id", 42L, "appId", APP)));
        DesignWriteStamper stamper = new DesignWriteStamper(ms);
        Map<String, Object> row = row("id", 500L, "appId", APP, "envId", 42L, "modelName", "Customer");

        stamper.stampCreate(row);

        assertEquals(500L, row.get("id"), "a client-supplied id is retained");
        assertEquals(42L, row.get("envId"), "client-supplied envId (validated as belonging to the app) is respected");
    }

    @Test
    @DisplayName("create tolerates string-typed id/appId/envId (JS clients send 64-bit ids as strings) — no ClassCastException")
    void createToleratesStringIds() {
        ModelService<Long> ms = mock(ModelService.class);
        when(ms.searchList(eq("DesignAppEnv"), any(FlexQuery.class))).thenReturn(List.of(row("id", 42L, "appId", APP)));
        DesignWriteStamper stamper = new DesignWriteStamper(ms);
        // A browser / JS client commonly serialises 64-bit ids as JSON strings to avoid precision loss.
        Map<String, Object> row = row("id", "500", "appId", "7", "envId", "42", "modelName", "Customer");

        stamper.stampCreate(row);   // must not throw ClassCastException on the raw string values

        assertEquals("500", row.get("id"), "a present (string) id is retained, not regenerated");
        assertEquals("42", row.get("envId"), "the client-supplied (string) envId is kept after validation");
    }

    @Test
    @DisplayName("create rejects a client-supplied envId that does not belong to the row's app")
    void createRejectsCrossAppEnv() {
        ModelService<Long> ms = mock(ModelService.class);
        // no env with (id=999, appId=APP) → cross-app injection rejected.
        when(ms.searchList(eq("DesignAppEnv"), any(FlexQuery.class))).thenReturn(List.of());
        DesignWriteStamper stamper = new DesignWriteStamper(ms);
        Map<String, Object> row = row("id", 500L, "appId", APP, "envId", 999L, "modelName", "Customer");

        assertThrows(RuntimeException.class, () -> stamper.stampCreate(row));
    }

    @Test
    @DisplayName("create without envId defaults to the app's lowest active env")
    void createDefaultsEnvToLowestActive() {
        ModelService<Long> ms = mock(ModelService.class);
        when(ms.searchList(eq("DesignAppEnv"), any(FlexQuery.class))).thenReturn(List.of(
                row("id", 9L, "active", true), row("id", 4L, "active", true)));
        DesignWriteStamper stamper = new DesignWriteStamper(ms);
        Map<String, Object> row = row("id", 500L, "appId", APP, "modelName", "Customer");

        stamper.stampCreate(row);

        assertEquals(4L, row.get("envId"), "defaults to the lowest active env id");
    }

    @Test
    @DisplayName("create without envId and no active env fails fast")
    void createWithoutAnyActiveEnvFails() {
        ModelService<Long> ms = mock(ModelService.class);
        when(ms.searchList(eq("DesignAppEnv"), any(FlexQuery.class))).thenReturn(List.of());
        DesignWriteStamper stamper = new DesignWriteStamper(ms);
        Map<String, Object> row = row("id", 500L, "appId", APP, "modelName", "Customer");

        assertThrows(RuntimeException.class, () -> stamper.stampCreate(row));
    }

    @Test
    @DisplayName("update strips the set-once envId so an edit can't re-home a row to another env")
    void updateFreezesEnv() {
        DesignWriteStamper stamper = new DesignWriteStamper(mock(ModelService.class));
        // A rename (e.g. modelName/fieldName change) rides through untouched — publish/merge pair the
        // row by business key (+ renamedFrom bridge), so the stamper only freezes the per-env identity.
        Map<String, Object> row = row("id", 500L, "envId", 99L, "modelName", "Renamed");

        stamper.stampUpdate(row);

        assertFalse(row.containsKey("envId"), "envId is set-once — stripped on update");
        assertEquals(500L, row.get("id"), "id (update target) is retained");
        assertEquals("Renamed", row.get("modelName"), "business attrs (incl. a renamed key) pass through");
    }
}
