package io.softa.framework.base.placeholder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateBlocksTest {

    private static final String LOOP_TABLE = "<table data-tpl-loop=\"lines\" data-tpl-model=\"OrderLine\">"
            + "<thead><tr><th data-tpl-field=\"name\">Name</th><th data-tpl-field=\"qty\">Qty</th></tr></thead>"
            + "<tbody><tr><td>{{name}}</td><td>{{qty}}</td></tr></tbody></table>";

    /** Renders the way a mail body is rendered: blocks translated, conditions reading the variables. */
    private static String render(String html, Map<String, Object> variables) {
        Map<String, Object> context = new HashMap<>(variables);
        context.putIfAbsent(TemplateBlocks.RAW_ROOT, variables);
        return TemplateEngine.renderHtml(TemplateBlocks.toPebble(html), context);
    }

    private static String condition(String field, String op, String value, String type) {
        return "<div data-tpl-if=\"" + field + "\" data-tpl-op=\"" + op + "\""
                + (value == null ? "" : " data-tpl-value=\"" + value + "\"")
                + (type == null ? "" : " data-tpl-value-type=\"" + type + "\"")
                + "><p>shown</p></div>";
    }

    @Test
    void templateWithoutBlocksIsReturnedUntouched() {
        String html = "<table>{% for l in lines %}<tr><td>{{ l.name }}</td></tr>{% endfor %}</table>";
        assertSame(html, TemplateBlocks.toPebble(html));
        assertFalse(TemplateBlocks.hasBlocks(html));
        assertTrue(TemplateBlocks.loops(html).isEmpty());
        assertNull(TemplateBlocks.toPebble(null));
    }

    @Test
    void loopTableRepeatsItsBodyRowForEachItem() {
        String out = render(LOOP_TABLE, Map.of("lines", List.of(
                Map.of("name", "Pen", "qty", 2), Map.of("name", "Ink", "qty", 5))));
        assertTrue(out.contains("<tbody><tr><td>Pen</td><td>2</td></tr><tr><td>Ink</td><td>5</td></tr></tbody>"), out);
        assertTrue(out.contains("<th data-tpl-field=\"name\">Name</th>"), out);
    }

    @Test
    void emptyListLeavesOnlyTheHeader() {
        String out = render(LOOP_TABLE, Map.of("lines", List.of()));
        assertTrue(out.contains("<tbody></tbody>"), out);
        assertTrue(out.contains("<thead>"), out);
    }

    @Test
    void loopCellValuesAreEscaped() {
        String out = render(LOOP_TABLE, Map.of("lines", List.of(Map.of("name", "<b>x</b>", "qty", 1))));
        assertTrue(out.contains("<td>&lt;b&gt;x&lt;/b&gt;</td>"), out);
    }

    @Test
    void loopsListTheirListAndColumns() {
        List<TemplateBlocks.Loop> loops = TemplateBlocks.loops("<p>Hi</p>" + LOOP_TABLE);
        assertEquals(List.of(new TemplateBlocks.Loop("lines", List.of("name", "qty"))), loops);
    }

    @Test
    void conditionsListWhatTheBlockStores() {
        List<TemplateBlocks.Condition> conditions =
                TemplateBlocks.conditions(condition("amount", "gt", "1000", "number") + condition("urgent", "isTrue", null, null));
        assertEquals(List.of(
                new TemplateBlocks.Condition("amount", "gt", "1000", "number"),
                new TemplateBlocks.Condition("urgent", "isTrue", null, "")), conditions);
    }

    @ParameterizedTest
    @CsvSource({
            "isTrue,,,true,true", "isTrue,,,false,false", "isTrue,,,,false",
            "isFalse,,,false,true", "isFalse,,,true,false", "isFalse,,,,true",
            "isEmpty,,,,true", "isEmpty,,,'',true", "isEmpty,,,x,false",
            "isNotEmpty,,,x,true", "isNotEmpty,,,,false",
            "eq,Approved,string,Approved,true", "eq,Approved,string,Draft,false",
            "ne,Approved,string,Draft,true", "ne,Approved,string,Approved,false",
            "eq,3,number,3,true", "eq,3,number,4,false", "eq,true,boolean,true,true",
            "gt,3,number,4,true", "gt,3,number,3,false", "gt,3,number,,false",
            "ge,3,number,3,true", "ge,3,number,2,false",
            "lt,3,number,2,true", "lt,3,number,3,false", "lt,3,number,,false",
            "le,3,number,3,true", "le,3,number,4,false",
    })
    void eachComparisonShowsOrHidesItsBlock(String op, String value, String type, String actual, boolean shown) {
        Map<String, Object> variables = new HashMap<>();
        Object typed = actual;
        if (actual != null && ("number".equals(type) || op.startsWith("g") || op.startsWith("l"))) {
            typed = Integer.valueOf(actual);
        } else if (actual != null && (op.startsWith("is") && ("true".equals(actual) || "false".equals(actual))
                || "boolean".equals(type))) {
            typed = Boolean.valueOf(actual);
        }
        variables.put("field", typed);
        String out = render(condition("field", op, value, type), variables);
        assertEquals(shown, out.contains("shown"), op + " " + value + " against " + actual + " → " + out);
    }

    @Test
    void aValueThatArrivesAsTextIsComparedAsWhatItIs() {
        // A preview form, a mapped flow variable: numbers and booleans often arrive as text.
        assertTrue(render(condition("field", "gt", "1000", "number"), Map.of("field", "1001")).contains("shown"));
        assertFalse(render(condition("field", "gt", "1000", "number"), Map.of("field", "999.5")).contains("shown"));
        assertTrue(render(condition("field", "eq", "3", "number"), Map.of("field", "3.0")).contains("shown"));
        assertTrue(render(condition("field", "eq", "3", "number"), Map.of("field", 3L)).contains("shown"));
        assertTrue(render(condition("field", "isTrue", null, null), Map.of("field", "true")).contains("shown"));
        assertTrue(render(condition("field", "eq", "true", "boolean"), Map.of("field", "TRUE")).contains("shown"));
        // Text that is no number is simply not greater, not an error that fails the send.
        assertFalse(render(condition("field", "gt", "1000", "number"), Map.of("field", "lots")).contains("shown"));
        assertFalse(render(condition("field", "le", "1000", "number"), Map.of("field", "lots")).contains("shown"));
        // A number compared as text reads as its text.
        assertTrue(render(condition("field", "eq", "42", "string"), Map.of("field", 42)).contains("shown"));
        // A list is empty or not by what it holds.
        assertTrue(render(condition("field", "isEmpty", null, null), Map.of("field", List.of())).contains("shown"));
        assertTrue(render(condition("field", "isNotEmpty", null, null), Map.of("field", List.of(1))).contains("shown"));
        assertTrue(render(condition("field", "isEmpty", null, null), Map.of("field", "  ")).contains("shown"));
    }

    @Test
    void holdsDecidesWithoutTheEngine() {
        assertTrue(TemplateCondition.holds(null, "isFalse", null, null));
        assertTrue(TemplateCondition.holds(null, "eq", "", "string"));
        assertFalse(TemplateCondition.holds(null, "eq", "0", "number"));
        assertTrue(TemplateCondition.holds(null, "ne", "0", "number"));
        assertTrue(TemplateCondition.holds(new java.math.BigDecimal("10.50"), "ge", "10.5", "number"));
        assertThrows(IllegalArgumentException.class, () -> TemplateCondition.holds(1, "between", "1", "number"));
    }

    @Test
    void conditionReadsTheRawValueNotTheDisplayedOne() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("status", "Approved by manager");
        variables.put(TemplateBlocks.RAW_ROOT, Map.of("status", "APPROVED"));
        String html = "<p>{{ status }}</p>" + condition("status", "eq", "APPROVED", "string");
        String out = render(html, variables);
        assertTrue(out.contains("<p>Approved by manager</p>"), out);
        assertTrue(out.contains("shown"), out);
    }

    @Test
    void conditionValueCannotCarryTemplateSyntax() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("name", "it's #{secret} \"x\" \\ %} {{ secret }}");
        variables.put("secret", "LEAK");
        String html = "<div data-tpl-if=\"name\" data-tpl-op=\"eq\""
                + " data-tpl-value=\"it's #{secret} &quot;x&quot; \\ %} {{ secret }}\"><p>shown</p></div>";
        String out = render(html, variables);
        assertTrue(out.contains("shown"), out);
        assertFalse(out.contains("<p>LEAK"), out);
    }

    @Test
    void conditionOnARelationPathFollowsThePath() {
        String out = render(condition("department.name", "eq", "Sales", null),
                Map.of("department", Map.of("name", "Sales")));
        assertTrue(out.contains("shown"), out);
    }

    @Test
    void blocksAndHandWrittenTagsLiveInOneTemplate() {
        String html = "<p>{% if count > 1 %}Many{% else %}Few{% endif %} "
                + "<a href=\"{% if urgent %}/u{% else %}/n{% endif %}?x={{ count }}\">go</a></p>"
                + "<table><tbody>{% for l in lines %}<tr><td>{{ l.name | upper }}</td>"
                + "<td>{% if l.qty > 2 %}big{% endif %}</td></tr>{% endfor %}</tbody></table>"
                + "{# a note #}"
                + condition("count", "gt", "1", "number")
                + LOOP_TABLE;
        Map<String, Object> variables = Map.of("count", 3, "urgent", true, "lines", List.of(
                Map.of("name", "a", "qty", 2), Map.of("name", "b", "qty", 3)));
        String out = render(html, variables);
        assertTrue(out.contains("<p>Many <a href=\"/u?x=3\">go</a></p>"), out);
        assertTrue(out.contains("<table><tbody><tr><td>A</td><td></td></tr><tr><td>B</td><td>big</td></tr></tbody></table>"), out);
        assertTrue(out.contains("shown"), out);
        assertTrue(out.contains("<tbody><tr><td>a</td><td>2</td></tr><tr><td>b</td><td>3</td></tr></tbody>"), out);
        assertFalse(out.contains("a note"), out);
        assertFalse(out.contains("@@tpl-"), out);
    }

    @Test
    void conditionMayWrapALoopTableAndFieldBlocks() {
        String html = "<div data-tpl-if=\"lines\" data-tpl-op=\"isNotEmpty\"><p>"
                + "<span data-tpl-field=\"title\" data-tpl-label=\"Title\">{{title}}</span></p>" + LOOP_TABLE + "</div>";
        String shown = render(html, Map.of("title", "Order", "lines", List.of(Map.of("name", "Pen", "qty", 1))));
        assertTrue(shown.contains(">Order</span>") && shown.contains("<td>Pen</td>"), shown);
        String hidden = render(html, Map.of("title", "Order", "lines", List.of()));
        assertFalse(hidden.contains("Order"), hidden);
    }

    @Test
    void fullDocumentKeepsItsHeadAndDoctype() {
        String html = "<!DOCTYPE html><html><head><style>p{color:red}</style></head><body>"
                + condition("urgent", "isTrue", null, null) + "</body></html>";
        String out = render(html, Map.of("urgent", true));
        assertTrue(out.toLowerCase().startsWith("<!doctype html>"), out);
        assertTrue(out.contains("<style>p{color:red}</style>") && out.contains("shown"), out);
    }

    @Test
    void aTemplateThatAlreadyContainsTheMarkerStillTranslates() {
        String html = "<p>@@tpl-0@@ <!--@@tpl-0@@--> {{ name }}</p>" + condition("urgent", "isTrue", null, null);
        String out = render(html, Map.of("name", "Alex", "urgent", true));
        assertTrue(out.contains("<p>@@tpl-0@@ <!--@@tpl-0@@--> Alex</p>"), out);
        assertTrue(out.contains("shown"), out);
    }

    @Test
    void untranslatableBlocksSayWhatIsWrong() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TemplateBlocks.toPebble(
                "<table data-tpl-loop=\"lines %} x\"><tbody><tr><td>{{a}}</td></tr></tbody></table>"))
                .getMessage().contains("not a field name"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TemplateBlocks.toPebble(
                "<table data-tpl-loop=\"lines\"><thead><tr><th>A</th></tr></thead></table>"))
                .getMessage().contains("no row to repeat"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TemplateBlocks.toPebble(
                condition("a b", "isTrue", null, null))).getMessage().contains("not a field name"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TemplateBlocks.toPebble(
                condition("amount", "between", "1", null))).getMessage().contains("'between'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TemplateBlocks.toPebble(
                condition("amount", "gt", "lots", "number"))).getMessage().contains("'lots' is not a number"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TemplateBlocks.toPebble(
                condition("amount", "eq", "1", "date"))).getMessage().contains("string, number or boolean"));
    }

    @Test
    void syntaxProblemNamesABrokenTemplateAndPassesAGoodOne() {
        assertNull(TemplateEngine.syntaxProblem("<p>{% if a > 1 %}x{% endif %} {{ b }}</p>"));
        assertNull(TemplateEngine.syntaxProblem(TemplateBlocks.toPebble(LOOP_TABLE)));
        assertNull(TemplateEngine.syntaxProblem(null));
        String problem = TemplateEngine.syntaxProblem("<p>{% if a &gt; 1 %}x{% endif %}</p>");
        assertTrue(problem != null && !problem.isBlank());
        assertTrue(TemplateEngine.syntaxProblem("{% for x in y %}never closed") != null);
    }
}
