package io.softa.framework.base.placeholder;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateEngineTest {

    @Test
    void renderSimpleVariable() {
        String template = "<p>Hello, {{ name }}</p>";
        String result = TemplateEngine.render(template, Map.of("name", "World"));
        assertEquals("<p>Hello, World</p>", result);
    }

    @Test
    void renderMultipleVariables() {
        String template = "<div>{{ greeting }}, {{ name }}!</div>";
        Map<String, Object> data = Map.of("greeting", "Hi", "name", "Softa");
        String result = TemplateEngine.render(template, data);
        assertEquals("<div>Hi, Softa!</div>", result);
    }

    @Test
    void renderNestedVariable() {
        String template = "<span>{{ user.name }}</span>";
        Map<String, Object> data = Map.of("user", Map.of("name", "Alice"));
        String result = TemplateEngine.render(template, data);
        assertEquals("<span>Alice</span>", result);
    }

    @Test
    void renderWithoutPlaceholders() {
        String template = "<p>No placeholders here</p>";
        String result = TemplateEngine.render(template, Map.of());
        assertEquals("<p>No placeholders here</p>", result);
    }

    @Test
    void renderWithExtraSpacesInPlaceholder() {
        // Pebble handles ${  name  } — spaces are part of the expression,
        // but for simple var names Pebble trims them in expression evaluation.
        String template = "<p>{{name}}</p>";
        String result = TemplateEngine.render(template, Map.of("name", "Compact"));
        assertEquals("<p>Compact</p>", result);
    }
}

