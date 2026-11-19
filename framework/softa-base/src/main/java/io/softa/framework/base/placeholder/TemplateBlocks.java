package io.softa.framework.base.placeholder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

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
 * {@code {{ }}} beside the blocks. Translating means parsing the HTML, which would do to those what
 * an editor does, so each is first swapped for an HTML comment — the one thing a parser leaves in
 * place anywhere, a table included — and swapped back, byte for byte, in the output.
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
    private static final Pattern FULL_DOCUMENT = Pattern.compile("<html[\\s>]", Pattern.CASE_INSENSITIVE);

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
            for (Element block : shield(html).document.select("[" + IF_ATTR + "]")) {
                conditions.add(new Condition(block.attr(IF_ATTR).trim(), block.attr(OP_ATTR).trim(),
                        block.hasAttr(VALUE_ATTR) ? block.attr(VALUE_ATTR) : null,
                        block.attr(VALUE_TYPE_ATTR).trim()));
            }
        }
        return conditions;
    }

    /**
     * The template with every block written out as the engine's {@code for} / {@code if}. A template
     * without blocks is returned as it is, untouched.
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
        Map<String, String> generated = new LinkedHashMap<>();
        for (Element table : shielded.document.select("table[" + LOOP_ATTR + "]")) {
            translateLoop(table, shielded, generated);
        }
        for (Element block : shielded.document.select("[" + IF_ATTR + "]")) {
            translateCondition(block, shielded, generated);
        }
        String out = shielded.fullDocument ? shielded.document.outerHtml() : shielded.document.body().html();
        for (Map.Entry<String, String> tag : generated.entrySet()) {
            out = out.replace(tag.getKey(), tag.getValue());
        }
        return shielded.restore(out);
    }

    private static void translateLoop(Element table, Shielded shielded, Map<String, String> generated) {
        String list = table.attr(LOOP_ATTR).trim();
        if (!PATH.matcher(list).matches()) {
            throw new IllegalArgumentException("A loop table names the list '" + list
                    + "', which is not a field name.");
        }
        Element row = bodyRowOf(table);
        if (row == null) {
            throw new IllegalArgumentException("The loop table over '" + list + "' has no row to repeat.");
        }
        // Inside the row a printed field is the item's: {{name}} reads {{ tplRow.name }}.
        for (Comment placeholder : commentsIn(row)) {
            Integer index = shielded.indexOf(placeholder.getData());
            if (index == null) {
                continue;
            }
            Matcher printed = PRINTED_PATH.matcher(shielded.tokens.get(index));
            if (printed.matches()) {
                shielded.tokens.set(index, "{{ " + ROW_VAR + "." + printed.group(1) + " }}");
            }
        }
        row.before(new TextNode(mark(generated, shielded, "{% for " + ROW_VAR + " in " + list + " %}")));
        row.after(new TextNode(mark(generated, shielded, "{% endfor %}")));
    }

    private static void translateCondition(Element block, Shielded shielded, Map<String, String> generated) {
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
            expression = op.expression(RAW_ROOT + "." + field, block.attr(VALUE_ATTR), type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The condition on '" + field + "': " + e.getMessage());
        }
        block.before(new TextNode(mark(generated, shielded, "{% if " + expression + " %}")));
        block.after(new TextNode(mark(generated, shielded, "{% endif %}")));
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
        if (row != null) {
            for (Comment placeholder : commentsIn(row)) {
                Integer index = shielded.indexOf(placeholder.getData());
                Matcher printed = index == null ? null : PRINTED_PATH.matcher(shielded.tokens.get(index));
                if (printed != null && printed.matches() && !columns.contains(printed.group(1))) {
                    columns.add(printed.group(1));
                }
            }
        }
        return columns;
    }

    private static List<Comment> commentsIn(Element element) {
        List<Comment> comments = new ArrayList<>();
        element.traverse((Node node, int depth) -> {
            if (node instanceof Comment comment) {
                comments.add(comment);
            }
        });
        return comments;
    }

    /** A stand-in for a generated tag, replaced by the tag once the document is a string again. */
    private static String mark(Map<String, String> generated, Shielded shielded, String tag) {
        String key = shielded.marker + "g" + generated.size() + "@@";
        generated.put(key, tag);
        return key;
    }

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
            syntax.appendReplacement(shielded,
                    Matcher.quoteReplacement("<!--" + marker + (tokens.size() - 1) + "@@-->"));
        }
        syntax.appendTail(shielded);
        boolean fullDocument = FULL_DOCUMENT.matcher(html).find();
        Document document = fullDocument
                ? Jsoup.parse(shielded.toString())
                : Jsoup.parseBodyFragment(shielded.toString());
        document.outputSettings().prettyPrint(false);
        return new Shielded(document, tokens, marker, fullDocument);
    }

    /** A parsed template whose own template syntax is held aside, to be put back unchanged. */
    private record Shielded(Document document, List<String> tokens, String marker, boolean fullDocument) {

        /** The token a comment stands in for, or null when it is a comment of the template's own. */
        Integer indexOf(String commentData) {
            if (!commentData.startsWith(marker) || !commentData.endsWith("@@")) {
                return null;
            }
            try {
                int index = Integer.parseInt(commentData.substring(marker.length(), commentData.length() - 2));
                return index >= 0 && index < tokens.size() ? index : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String restore(String out) {
            String restored = out;
            for (int i = 0; i < tokens.size(); i++) {
                String inner = marker + i + "@@";
                // In text a stand-in comes back as the comment; inside an attribute value, escaped.
                restored = restored.replace("<!--" + inner + "-->", tokens.get(i))
                        .replace("&lt;!--" + inner + "--&gt;", tokens.get(i))
                        .replace("&lt;!--" + inner + "-->", tokens.get(i));
            }
            return restored;
        }
    }
}
