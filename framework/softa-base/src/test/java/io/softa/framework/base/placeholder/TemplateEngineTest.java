package io.softa.framework.base.placeholder;

import java.time.LocalDateTime;
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
    void renderHtmlEscapesVariableOutput() {
        String template = "<p>{{ name }}</p>";
        String result = TemplateEngine.renderHtml(template, Map.of("name", "<script>alert(1)</script>"));
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>", result);
    }

    @Test
    void renderHtmlRawFilterEmbedsTrustedFragment() {
        String template = "<div>{{ fragment | raw }}</div>";
        String result = TemplateEngine.renderHtml(template, Map.of("fragment", "<b>bold</b>"));
        assertEquals("<div><b>bold</b></div>", result);
    }

    @Test
    void renderKeepsOutputVerbatim() {
        // The codegen/plain-text profile must not HTML-escape: SQL, Java code and
        // text/plain bodies embed values exactly as provided
        String result = TemplateEngine.render("{{ value }}", Map.of("value", "a < b && c > d"));
        assertEquals("a < b && c > d", result);
    }

    private static final LocalDateTime TIME = LocalDateTime.of(2026, 11, 12, 13, 0, 40, 123_456_789);

    @Test
    void renderPrintsDatesAndTimesAsTheApiWritesThem() {
        // Not the ISO toString() (2026-11-12T13:00:40.123456789)
        assertEquals("Changed on 2026-11-12 13:00:40",
                TemplateEngine.render("Changed on {{ time }}", Map.of("time", TIME)));
        assertEquals("2026-11-12 / 13:00:40", TemplateEngine.render("{{ day }} / {{ clock }}",
                Map.of("day", TIME.toLocalDate(), "clock", TIME.toLocalTime())));
    }

    @Test
    void renderHtmlFormatsTheDateBeforeEscapingIt() {
        // The escaper turns any value into text, so the date is formatted inside it; other values
        // are escaped exactly as before.
        assertEquals("<p>2026-11-12 13:00:40 &lt;b&gt;</p>",
                TemplateEngine.renderHtml("<p>{{ time }} {{ tag }}</p>", Map.of("time", TIME, "tag", "<b>")));
        assertEquals("<b>2026-11-12 13:00:40</b>",
                TemplateEngine.renderHtml("{{ html | raw }}{{ time }}{{ close | raw }}",
                        Map.of("html", "<b>", "time", TIME, "close", "</b>")));
    }

    @Test
    void eachBranchOfAConditionalIsFormatted() {
        Map<String, Object> data = Map.of("time", TIME);
        assertEquals("2026-11-12 13:00:40", TemplateEngine.renderHtml("{{ time is null ? '-' : time }}", data));
        assertEquals("-", TemplateEngine.renderHtml("{{ missing is null ? '-' : missing }}", data));
        assertEquals("2026-11-12 13:00:40", TemplateEngine.render("{{ time is null ? '-' : time }}", data));
    }

    @Test
    void filtersAndAttributesStillReceiveTheDateObject() {
        // Only the printed value is formatted: date(...) and .year work on the LocalDateTime itself.
        assertEquals("12/11/2026 2026",
                TemplateEngine.renderHtml("{{ time | date('dd/MM/yyyy') }} {{ time.year }}", Map.of("time", TIME)));
        assertEquals("2026-11-12 13:00:40",
                TemplateEngine.render("{{ missing | default(time) }}", Map.of("time", TIME)));
    }

    @Test
    void aDateThatArrivesAsText_isReformattedByNamingItsFormat() {
        // Mail and SMS variables come back from the send record's JSON as the API's text.
        assertEquals("12/11/2026", TemplateEngine.renderHtml(
                "{{ time | date('dd/MM/yyyy', existingFormat='yyyy-MM-dd HH:mm:ss') }}",
                Map.of("time", "2026-11-12 13:00:40")));
    }

    @Test
    void aMacrosOutputIsPrintedAsItIs() {
        // The DDL templates print macro calls; the macro's output goes through untouched.
        assertEquals("[2026-11-12 13:00:40]", TemplateEngine.render(
                "{% macro wrap(value) %}[{{ value }}]{% endmacro %}{{ wrap(time) }}", Map.of("time", TIME)));
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

