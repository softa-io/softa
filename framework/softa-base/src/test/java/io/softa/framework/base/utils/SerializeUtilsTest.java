package io.softa.framework.base.utils;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.exception.IllegalArgumentException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deserialization admits the framework's own types plus JDK values and collections, and refuses
 * any other class while the stream is read — not after the object has already been built.
 */
class SerializeUtilsTest {

    @Test
    void roundTripsFrameworkAndJdkValueTypes() {
        Context ctx = new Context();
        ctx.setTenantId(7L);
        Map<String, Object> payload = new HashMap<>();
        payload.put("context", ctx);
        payload.put("ids", new ArrayList<>(List.of(1L, 2L)));
        payload.put("date", LocalDate.of(2026, 1, 2));
        payload.put("amount", new BigDecimal("12.50"));
        payload.put("args", new Object[]{"name", 3});
        payload.put("immutable", List.of("a", "b"));
        payload.put("json", JsonUtils.stringToObject("{\"k\":[1,\"v\"]}", JsonNode.class));

        Map<?, ?> back = SerializeUtils.deserialize(SerializeUtils.serialize(payload), Map.class);

        assertThat(((Context) back.get("context")).getTenantId()).isEqualTo(7L);
        assertThat(back.get("ids")).isEqualTo(List.of(1L, 2L));
        assertThat(back.get("date")).isEqualTo(LocalDate.of(2026, 1, 2));
        assertThat(back.get("amount")).isEqualTo(new BigDecimal("12.50"));
        assertThat(back.get("immutable")).isEqualTo(List.of("a", "b"));
        assertThat(back.get("json").toString()).isEqualTo("{\"k\":[1,\"v\"]}");
    }

    @Test
    void refusesAClassOutsideTheAllowedPackages() throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("nested", URI.create("http://example.invalid/").toURL());
        String serialized = SerializeUtils.serialize(payload);

        assertThatThrownBy(() -> SerializeUtils.deserialize(serialized, Map.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    void refusesSubpackagesOfAnAllowedJdkPackage() {
        String serialized = SerializeUtils.serialize(new java.util.concurrent.atomic.AtomicLong(1));

        assertThatThrownBy(() -> SerializeUtils.deserialize(serialized, Object.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rejected");
    }
}
