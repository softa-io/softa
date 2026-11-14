package io.softa.framework.base.placeholder;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    void replacePlaceholders() {
        Map<String, Object> variables = Map.of("userName", "Tom", "count", 3);
        // The documented spaced form and the compact form resolve the same key,
        // and non-string values are rendered via toString
        Assertions.assertEquals("Hello Tom, you have 3 tasks.",
                PlaceholderUtils.replacePlaceholders("Hello {{ userName }}, you have {{count}} tasks.", variables));
        // A variable missing from the map is left in place literally
        Assertions.assertEquals("Hi {{ unknown }}!",
                PlaceholderUtils.replacePlaceholders("Hi {{ unknown }}!", variables));
        // A resolved value containing placeholder syntax is not substituted again
        Assertions.assertEquals("{{ b }}",
                PlaceholderUtils.replacePlaceholders("{{ a }}", Map.of("a", "{{ b }}", "b", "x")));
        // Null text and null map pass through unchanged
        Assertions.assertNull(PlaceholderUtils.replacePlaceholders(null, variables));
        Assertions.assertEquals("Hi {{ userName }}",
                PlaceholderUtils.replacePlaceholders("Hi {{ userName }}", null));
    }

    @Test
    void replacePlaceholdersWritesDatesAsTheApiDoes() {
        Map<String, Object> variables = Map.of(
                "time", LocalDateTime.of(2026, 11, 12, 13, 0, 40, 123_456_789),
                "day", LocalDate.of(2026, 11, 12));
        // Not the ISO toString() (2026-11-12T13:00:40.123456789)
        Assertions.assertEquals("Changed on 2026-11-12 13:00:40, effective 2026-11-12.",
                PlaceholderUtils.replacePlaceholders("Changed on {{ time }}, effective {{day}}.", variables));
    }

    @Test
    void replacePlaceholder() {
        Assertions.assertEquals("Hello Tom!",
                PlaceholderUtils.replacePlaceholder("Hello {{ userName }}!", "userName", "Tom"));
        Assertions.assertEquals("Hello Tom!",
                PlaceholderUtils.replacePlaceholder("Hello {{userName}}!", "userName", "Tom"));
        // The replacement value is inserted literally, even with regex metacharacters
        Assertions.assertEquals("Rate: $5",
                PlaceholderUtils.replacePlaceholder("Rate: {{ rate }}", "rate", "$5"));
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
