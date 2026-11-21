package io.softa.framework.base.placeholder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Range;
import org.jsoup.parser.Parser;

import io.softa.framework.base.placeholder.TemplateCondition.ValueType;

/**
 * The two blocks an HTML template editor writes for what plain {@code {{ }}} cannot say — a table
 * that repeats a row for each item of a list, and a section shown only when a condition holds — and
 * their translation into the template engine's {@code for} and {@code if}.
 *
 * <p>A block is ordinary HTML carrying attributes, never template syntax:
 * <pre>{@code
 * <table data-tpl-loop="lines">
 *   <thead><tr><th data-tpl-field="name">Name</th></tr></thead>
 *   <tbody><tr><td>{{name}}</td></tr></tbody>
 * </table>
 * <div data-tpl-if="amount" data-tpl-op="gt" data-tpl-value="1000" data-tpl-value-type="number">…</div>
 * }</pre>
 * It has to be: a {@code {% for %}} around a table row sits between {@code <tbody>} and
 * {@code <tr>}, where HTML allows no text, so every parser — a rich-text editor's included — moves
 * it out of the table; and a {@code >} in a condition comes back from an HTML serializer as
 * {@code &gt;}, which the template engine refuses. {@link #toPebble} writes the tags in just before
 * rendering, into a string that is not parsed as HTML again.
 *
 * <p><b>Hand-written tags stay as written.</b> A template may carry its own {@code {% %}} and
 * {@code {{ }}} beside the blocks — in text, in a table, inside a start tag, in a comment. The HTML is
 * parsed only to find where the blocks are; the output is the template's own text with the generated
 * tags inserted at those places, so nothing else in it is rewritten.
 *
 * <p><b>Where a condition reads its field.</b> A condition compares the field's own value, while
 * the same field printed with {@code {{ }}} may be a display text (an option's label, a relation's
 * name). So a condition reads {@code _raw.<field>}: the caller puts the comparable values under
 * {@link #RAW_ROOT}, or points it at the variables themselves when the two are the same.
 */
public final class TemplateBlocks {

    /** Marks a table whose body row repeats for each item of the named list. */
    public static final String LOOP_ATTR = "data-tpl-loop";
    /** Marks an element shown only when the condition on the named field holds. */
    public static final String IF_ATTR = "data-tpl-if";
    public static final String OP_ATTR = "data-tpl-op";
    public static final String VALUE_ATTR = "data-tpl-value";
    public static final String VALUE_TYPE_ATTR = "data-tpl-value-type";
    /** On a loop table's header cell: the item field its column prints. */
    public static final String FIELD_ATTR = "data-tpl-field";

    /** The variable a condition reads its fields under; see the class comment. */
    public static final String RAW_ROOT = "_raw";

    /** The name each item of a loop table goes by inside its row. */
    static final String ROW_VAR = "tplRow";

    private static final Pattern PATH = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*");
    private static final Pattern PRINTED_PATH = Pattern.compile("^\\{\\{\\s*(" + PATH.pattern() + ")\\s*\\}\\}$");
    /** A template comment, a tag or an output, whichever starts first. */
    private static final Pattern TEMPLATE_SYNTAX =
            Pattern.compile("\\{#.*?#\\}|\\{%.*?%\\}|\\{\\{.*?\\}\\}", Pattern.DOTALL);

    private TemplateBlocks() {
    }

    /** A loop table: the list it repeats over and the item fields its columns print. */
    public record Loop(String list, List<String> columns) {
    }

    /** A condition block as stored: the field, the comparison, and the value compared against. */
    public record Condition(String field, String op, String value, String valueType) {
    }

    /** Whether the HTML carries a block at all; a template without one needs no translation. */
    public static boolean hasBlocks(String html) {
        return html != null && (html.contains(LOOP_ATTR) || html.contains(IF_ATTR));
    }

    /** The loop tables of a template, in document order. */
    public static List<Loop> loops(String html) {
        List<Loop> loops = new ArrayList<>();
        if (hasBlocks(html)) {
            Shielded shielded = shield(html);
            for (Element table : shielded.document.select("table[" + LOOP_ATTR + "]")) {
                loops.add(new Loop(table.attr(LOOP_ATTR).trim(), columnsOf(table, shielded)));
            }
        }
        return loops;
    }

    /** The condition blocks of a template, in document order. */
    public static List<Condition> conditions(String html) {
        List<Condition> conditions = new ArrayList<>();
        if (hasBlocks(html)) {
            Shielded shielded = shield(html);
            for (Element block : shielded.document.select("[" + IF_ATTR + "]")) {
                conditions.add(new Condition(block.attr(IF_ATTR).trim(), block.attr(OP_ATTR).trim(),
                        block.hasAttr(VALUE_ATTR) ? shielded.restore(block.attr(VALUE_ATTR)) : null,
                        block.attr(VALUE_TYPE_ATTR).trim()));
            }
        }
        return conditions;
    }

    /**
     * The template with every block written out as the engine's {@code for} / {@code if}. A template
     * without blocks is returned as it is, untouched; one with blocks keeps every character it had —
     * the generated tags are inserted around the blocks, nothing is re-serialized.
     *
     * @throws IllegalArgumentException naming the block that cannot be translated: a list or field
     *                                  that is not a plain path, an unknown comparison, a value that
     *                                  is not of the type the block says
     */
    public static String toPebble(String html) {
        if (!hasBlocks(html)) {
            return html;
        }
        Shielded shielded = shield(html);
        List<Insert> inserts = new ArrayList<>();
        for (Element table : shielded.document.select("table[" + LOOP_ATTR + "]")) {
            translateLoop(table, shielded, inserts);
        }
        for (Element block : shielded.document.select("[" + IF_ATTR + "]")) {
            translateCondition(block, shielded, inserts);
        }
        // At one position a block that ends comes before one that starts: </div><div> → endif, if.
        inserts.sort(Comparator.comparingInt(Insert::position).thenComparing(Insert::opens));
        StringBuilder out = new StringBuilder(shielded.text.length() + 64 * inserts.size());
        int from = 0;
        for (Insert insert : inserts) {
            out.append(shielded.restore(shielded.text.substring(from, insert.position()))).append(insert.tag());
            from = insert.position();
        }
        out.append(shielded.restore(shielded.text.substring(from)));
        return out.toString();
    }

    /** A generated tag and where it goes in the template's text. */
    private record Insert(int position, boolean opens, String tag) {
    }

    private static void translateLoop(Element table, Shielded shielded, List<Insert> inserts) {
        String list = table.attr(LOOP_ATTR).trim();
        if (!PATH.matcher(list).matches()) {
            throw new IllegalArgumentException("A loop table names the list '" + list
                    + "', which is not a field name.");
        }
        Element row = bodyRowOf(table);
        if (row == null) {
            throw new IllegalArgumentException("The loop table over '" + list + "' has no row to repeat.");
        }
        int start = startOf(row, "The loop table over '" + list + "'");
        int end = endOf(row, "The loop table over '" + list + "'");
        // Inside the row a printed field is the item's: {{name}} reads {{ tplRow.name }}.
        for (int index : shielded.tokensBetween(start, end)) {
            Matcher printed = PRINTED_PATH.matcher(shielded.tokens.get(index));
            if (printed.matches()) {
                shielded.tokens.set(index, "{{ " + ROW_VAR + "." + printed.group(1) + " }}");
            }
        }
        inserts.add(new Insert(start, true, "{% for " + ROW_VAR + " in " + list + " %}"));
        inserts.add(new Insert(end, false, "{% endfor %}"));
    }

    private static void translateCondition(Element block, Shielded shielded, List<Insert> inserts) {
        String field = block.attr(IF_ATTR).trim();
        if (!PATH.matcher(field).matches()) {
            throw new IllegalArgumentException("A condition names the field '" + field
                    + "', which is not a field name.");
        }
        TemplateCondition op = TemplateCondition.of(block.attr(OP_ATTR)).orElseThrow(() ->
                new IllegalArgumentException("The condition on '" + field + "' uses the comparison '"
                        + block.attr(OP_ATTR) + "', which is not one of the comparisons a condition can make."));
        ValueType type = ValueType.of(block.attr(VALUE_TYPE_ATTR)).orElseThrow(() ->
                new IllegalArgumentException("The condition on '" + field + "' compares against a '"
                        + block.attr(VALUE_TYPE_ATTR) + "' value; it must be string, number or boolean."));
        String expression;
        try {
            // The value as written, template syntax included: quoted as one literal, it stays text.
            expression = op.expression(RAW_ROOT + "." + field, shielded.restore(block.attr(VALUE_ATTR)), type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The condition on '" + field + "': " + e.getMessage());
        }
        String where = "The condition on '" + field + "'";
        inserts.add(new Insert(startOf(block, where), true, "{% if " + expression + " %}"));
        inserts.add(new Insert(endOf(block, where), false, "{% endif %}"));
    }

    /** Where an element's start tag begins in the template's text. */
    private static int startOf(Element element, String what) {
        Range range = element.sourceRange();
        if (!range.isTracked() || range.isImplicit()) {
            throw new IllegalArgumentException(what + " could not be located in the template.");
        }
        return range.start().pos();
    }

    /**
     * Where an element ends in the template's text: after its end tag, or — when the HTML leaves the
     * end tag out — where the parser closed it.
     */
    private static int endOf(Element element, String what) {
        Range range = element.endSourceRange();
        if (!range.isTracked()) {
            throw new IllegalArgumentException(what + " could not be located in the template.");
        }
        return range.isImplicit() ? range.start().pos() : range.end().pos();
    }

    /** The row a loop table repeats: the first row of its body, or its first row outside the header. */
    private static Element bodyRowOf(Element table) {
        Element inBody = table.selectFirst("tbody > tr");
        if (inBody != null) {
            return inBody;
        }
        for (Element row : table.select("tr")) {
            Element parent = row.parent();
            if (parent == null || !"thead".equals(parent.normalName())) {
                return row;
            }
        }
        return null;
    }

    private static List<String> columnsOf(Element table, Shielded shielded) {
        List<String> columns = new ArrayList<>();
        for (Element header : table.select("thead th[" + FIELD_ATTR + "]")) {
            String field = header.attr(FIELD_ATTR).trim();
            if (!field.isEmpty() && !columns.contains(field)) {
                columns.add(field);
            }
        }
        Element row = bodyRowOf(table);
        Range start = row == null ? null : row.sourceRange();
        Range end = row == null ? null : row.endSourceRange();
        if (start != null && start.isTracked() && end.isTracked()) {
            int to = end.isImplicit() ? end.start().pos() : end.end().pos();
            for (int index : shielded.tokensBetween(start.start().pos(), to)) {
                Matcher printed = PRINTED_PATH.matcher(shielded.tokens.get(index));
                if (printed.matches() && !columns.contains(printed.group(1))) {
                    columns.add(printed.group(1));
                }
            }
        }
        return columns;
    }

    /**
     * The template with each piece of its own template syntax swapped for a plain-text stand-in, and
     * parsed with every element's place in that text recorded. Parsing only locates the blocks: what
     * the parser does to the stand-ins — move them out of a table, read them as an attribute — never
     * reaches the output, which is the text itself with tags inserted.
     */
    private static Shielded shield(String html) {
        // A marker the template does not already contain, so a stand-in is never mistaken for content.
        String marker = "@@tpl-";
        while (html.contains(marker)) {
            marker += "x";
        }
        List<String> tokens = new ArrayList<>();
        Matcher syntax = TEMPLATE_SYNTAX.matcher(html);
        StringBuilder shielded = new StringBuilder();
        while (syntax.find()) {
            tokens.add(syntax.group());
            syntax.appendReplacement(shielded, Matcher.quoteReplacement(marker + (tokens.size() - 1) + "@@"));
        }
        syntax.appendTail(shielded);
        String text = shielded.toString();
        Document document = Jsoup.parse(text, "", Parser.htmlParser().setTrackPosition(true));
        return new Shielded(document, text, tokens, marker,
                Pattern.compile(Pattern.quote(marker) + "(\\d+)@@"));
    }

    /** A parsed template whose own template syntax is held aside, to be put back unchanged. */
    private record Shielded(Document document, String text, List<String> tokens, String marker, Pattern standIn) {

        /** The tokens whose stand-ins lie in {@code [from, to)} of the shielded text, in order. */
        List<Integer> tokensBetween(int from, int to) {
            List<Integer> found = new ArrayList<>();
            Matcher matcher = standIn.matcher(text).region(Math.max(0, from), Math.min(text.length(), to));
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                if (index < tokens.size()) {
                    found.add(index);
                }
            }
            return found;
        }

        /** {@code part} with every stand-in replaced by the token it stands for. */
        String restore(String part) {
            if (part == null || part.isEmpty()) {
                return part;
            }
            Matcher matcher = standIn.matcher(part);
            StringBuilder restored = new StringBuilder();
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                matcher.appendReplacement(restored, Matcher.quoteReplacement(
                        index < tokens.size() ? tokens.get(index) : matcher.group()));
            }
            matcher.appendTail(restored);
            return restored.toString();
        }
    }
}
