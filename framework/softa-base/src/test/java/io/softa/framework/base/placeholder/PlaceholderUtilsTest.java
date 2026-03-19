package io.softa.framework.base.placeholder;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PlaceholderUtilsTest {

    @Test
    void parsePlaceholder() {
        Assertions.assertEquals(PlaceholderKind.VARIABLE,
                PlaceholderUtils.parsePlaceholder("{{ TriggerParams.status }}").getKind());
        Assertions.assertEquals(PlaceholderKind.EXPRESSION,
                PlaceholderUtils.parsePlaceholder("{{ TriggerParams.totalAmount > 0 }}").getKind());
        Assertions.assertEquals(PlaceholderKind.RESERVED_FIELD,
                PlaceholderUtils.parsePlaceholder("{{ @createdTime }}").getKind());
        Assertions.assertEquals("createdTime", PlaceholderUtils.parsePlaceholder("{{ @createdTime }}").getContent());
    }

    @Test
    void extractVariable() {
        PlaceholderToken token = PlaceholderUtils.parsePlaceholder("{{ TriggerParams.status }}");
        Assertions.assertEquals("PAID", PlaceholderUtils.extractVariable(token, Map.of("TriggerParams",
                Map.of("status", "PAID"))));
        token = PlaceholderUtils.parsePlaceholder("{{ TriggerParams.owner.profile.name }}");
        Assertions.assertEquals("Tom", PlaceholderUtils.extractVariable(token,
                Map.of("TriggerParams", Map.of("owner", Map.of("profile", Map.of("name", "Tom"))))));
        Map<String, Object> ownerMap = new HashMap<>();
        ownerMap.put("owner", null);
        Assertions.assertNull(PlaceholderUtils.extractVariable(token,
                Map.of("TriggerParams", ownerMap)));
    }
}
