package io.softa.starter.message.template;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.base.placeholder.TemplateBlocks;
import io.softa.starter.message.mail.dto.MailTemplateVariableDTO;
import io.softa.starter.message.mail.dto.TemplateVariableKind;
import io.softa.starter.message.mail.support.TemplateVariableExtractor;

/**
 * What a template asks of its variables: the names it prints, the lists it loops over, and what its
 * blocks need. The one reading of a template that both a save (is every name known to the source?)
 * and a send from a record (which fields must be read?) work from.
 *
 * <p>Only what can be recognised as a name is listed. {@code {{ a + b }}} names nothing here and is
 * neither checked nor read; {@code {{ name | upper }}} names {@code name}. Names local to the
 * template — a loop's item, a {@code set} target — are not variables and are left out.
 *
 * @param printed    names printed with {@code {{ }}}, dotted paths included
 * @param lists      names looped over, by a loop table or a hand-written {@code for}
 * @param loops      the loop tables, each with the item fields its columns print
 * @param conditions the condition blocks
 * @param blockError why the template's blocks cannot be translated, or null
 */
public record TemplateUsage(Set<String> printed, Set<String> lists, List<TemplateBlocks.Loop> loops,
                            List<TemplateBlocks.Condition> conditions, String blockError) {

    private static final String NAME = "[\\p{L}\\p{N}_]+(?:\\.[\\p{L}\\p{N}_]+)*";
    /** A name handed to a filter: {@code name | upper}. One bar — two are the engine's "or". */
    private static final Pattern FILTERED_NAME = Pattern.compile("^(" + NAME + ")\\s*\\|(?!\\|).*$", Pattern.DOTALL);
    private static final Pattern PLAIN_NAME = Pattern.compile("^" + NAME + "$");

    /** Whether the template names nothing a source could be asked about. */
    public boolean isEmpty() {
        return printed.isEmpty() && lists.isEmpty() && loops.isEmpty() && conditions.isEmpty();
    }

    /** A mail template: the subject and both bodies. */
    public static TemplateUsage ofMail(String subject, String bodyHtml, String bodyText) {
        List<TemplateBlocks.Loop> loops = List.of();
        List<TemplateBlocks.Condition> conditions = List.of();
        String blockError = null;
        // Read with the blocks written out: a loop table's row then prints its item, not variables
        // of the template, and a condition's field is not mistaken for a printed one.
        String html = bodyHtml;
        if (TemplateBlocks.hasBlocks(bodyHtml)) {
            try {
                html = TemplateBlocks.toPebble(bodyHtml);
                loops = TemplateBlocks.loops(bodyHtml);
                conditions = TemplateBlocks.conditions(bodyHtml);
            } catch (IllegalArgumentException e) {
                blockError = e.getMessage();
                html = bodyHtml;
            }
        }
        Set<String> printed = new LinkedHashSet<>();
        Set<String> lists = new LinkedHashSet<>();
        for (MailTemplateVariableDTO token : TemplateVariableExtractor.extractWritten(subject, html, bodyText)) {
            collect(token.getName(), token.getKind(), printed, lists);
        }
        return new TemplateUsage(printed, lists, loops, conditions, blockError);
    }

    /** A text rendered by plain substitution — an SMS content: its {@code {{ }}} names only. */
    public static TemplateUsage ofText(String content) {
        Set<String> printed = new LinkedHashSet<>();
        if (StringUtils.hasText(content)) {
            for (String name : PlaceholderUtils.extractVariables(content)) {
                if (PLAIN_NAME.matcher(name).matches()) {
                    printed.add(name);
                }
            }
        }
        return new TemplateUsage(printed, Set.of(), List.of(), List.of(), null);
    }

    private static void collect(String name, TemplateVariableKind kind, Set<String> printed, Set<String> lists) {
        switch (kind) {
            case VARIABLE -> printed.add(name);
            case COLLECTION -> lists.add(name);
            case EXPRESSION -> {
                Matcher filtered = FILTERED_NAME.matcher(name);
                if (filtered.matches()) {
                    printed.add(filtered.group(1));
                }
            }
            // A reserved field is the platform's own value, not one of the template's variables.
            case RESERVED_FIELD -> { }
        }
    }
}
