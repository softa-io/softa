package io.softa.starter.message.mail.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.util.StringUtils;

import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.placeholder.PlaceholderKind;
import io.softa.framework.base.placeholder.PlaceholderToken;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.starter.message.mail.dto.MailTemplateVariableDTO;
import io.softa.starter.message.mail.dto.TemplateVariableKind;
import io.softa.starter.message.mail.entity.AbstractMailTemplate;

/**
 * Extracts the distinct input-relevant tokens of a {@link AbstractMailTemplate} in
 * first-appearance order (subject, then bodyHtml, then bodyText — all stored
 * columns are scanned regardless of {@code bodyMode}: an extra variable
 * offered to the user is harmless, a missing one is not).
 *
 * <p>Two token sources:
 * <ul>
 * <li>{@code {{ ... }}} placeholders (all three columns). Classification
 * delegates to {@link PlaceholderUtils#parsePlaceholder} so the placeholder
 * grammar has a single source of truth; a unicode simple name the ASCII-only
 * framework pattern rejects is reclassified as {@link TemplateVariableKind#VARIABLE}
 * — both render paths substitute such names verbatim (map-key substitution
 * for the subject, Pebble for the bodies).</li>
 * <li>{@code {% ... %}} Pebble tags (bodies only — the subject renders via
 * {@code StringSubstitutor} and has no control flow): a
 * {@code {% for item in items %}} header surfaces {@code items} as
 * {@link TemplateVariableKind#COLLECTION}, and a bare-simple-name
 * {@code {% if flag %}} surfaces {@code flag} as a variable.</li>
 * </ul>
 *
 * <p><b>Template-local names are excluded</b>: loop variables
 * ({@code item}), Pebble's builtin {@code loop}, and {@code {% set %}}
 * targets are template-scoped — a {@code {{ item.name }}} reference must NOT
 * become an input (a supplied value would never be read). Locals are
 * collected template-wide (not block-scoped) for simplicity; a global
 * variable sharing a loop variable's name is ambiguous authoring and is
 * excluded too. Exclusion keys on the token's leading name segment, so
 * {@code {{ item.name | upper }}} is dropped with its root.
 */
public final class TemplateVariableExtractor {

    /**
     * A "simple name" the input UI can offer a field for: unicode letters /
     * digits / underscore, dot-separated. Deliberately wider than the
     * framework's ASCII-only variable pattern (see class javadoc).
     */
    private static final Pattern SIMPLE_NAME =
            Pattern.compile("^[\\p{L}\\p{N}_]+(?:\\.[\\p{L}\\p{N}_]+)*$");

    /** Both Pebble token forms in one pass, preserving document order. */
    private static final Pattern PEBBLE_TOKEN =
            Pattern.compile("\\{\\{(.+?)\\}\\}|\\{%(.+?)%\\}", Pattern.DOTALL);

    private static final Pattern PLACEHOLDER_ONLY = Pattern.compile(
            Pattern.quote(StringConstant.PLACEHOLDER_PREFIX) + "(.+?)"
                    + Pattern.quote(StringConstant.PLACEHOLDER_SUFFIX), Pattern.DOTALL);

    private static final Pattern FOR_TAG =
            Pattern.compile("^for\\s+([\\p{L}\\p{N}_]+)\\s+in\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern SET_TAG =
            Pattern.compile("^set\\s+([\\p{L}\\p{N}_]+)\\s*=");
    private static final Pattern IF_TAG =
            Pattern.compile("^(?:if|elseif)\\s+(.+)$", Pattern.DOTALL);

    /** Leading name segment of a token, for local-exclusion checks. */
    private static final Pattern LEADING_NAME = Pattern.compile("^([\\p{L}\\p{N}_]+)");

    private TemplateVariableExtractor() {
    }

    public static List<MailTemplateVariableDTO> extract(AbstractMailTemplate template) {
        List<String> pebbleColumns = new ArrayList<>();
        if (StringUtils.hasText(template.getBodyHtml())) {
            pebbleColumns.add(template.getBodyHtml());
        }
        if (StringUtils.hasText(template.getBodyText())) {
            pebbleColumns.add(template.getBodyText());
        }

        Set<String> locals = collectLocals(pebbleColumns);
        Map<String, TemplateVariableKind> entries = new LinkedHashMap<>();

        if (StringUtils.hasText(template.getSubject())) {
            scanPlaceholders(template.getSubject(), locals, entries);
        }
        for (String column : pebbleColumns) {
            scanPebble(column, locals, entries);
        }

        return entries.entrySet().stream()
                .map(e -> new MailTemplateVariableDTO(e.getKey(), e.getValue()))
                .toList();
    }

    /** Pass 1 over the Pebble columns: loop vars, {@code set} targets, builtin {@code loop}. */
    private static Set<String> collectLocals(List<String> pebbleColumns) {
        Set<String> locals = new LinkedHashSet<>();
        for (String column : pebbleColumns) {
            Matcher m = PEBBLE_TOKEN.matcher(column);
            while (m.find()) {
                String tag = m.group(2);
                if (tag == null) {
                    continue;
                }
                tag = tag.trim();
                Matcher forTag = FOR_TAG.matcher(tag);
                if (forTag.matches()) {
                    locals.add(forTag.group(1));
                    locals.add("loop");
                    continue;
                }
                Matcher setTag = SET_TAG.matcher(tag);
                if (setTag.find()) {
                    locals.add(setTag.group(1));
                }
            }
        }
        return locals;
    }

    /** Subject-style scan: {@code {{ }}} placeholders only. */
    private static void scanPlaceholders(String source, Set<String> locals,
                                         Map<String, TemplateVariableKind> entries) {
        Matcher m = PLACEHOLDER_ONLY.matcher(source);
        while (m.find()) {
            addPlaceholder(m.group(1).trim(), locals, entries);
        }
    }

    /** Pebble-column scan: placeholders and {@code {% %}} tags in document order. */
    private static void scanPebble(String source, Set<String> locals,
                                   Map<String, TemplateVariableKind> entries) {
        Matcher m = PEBBLE_TOKEN.matcher(source);
        while (m.find()) {
            if (m.group(1) != null) {
                addPlaceholder(m.group(1).trim(), locals, entries);
                continue;
            }
            String tag = m.group(2).trim();
            Matcher forTag = FOR_TAG.matcher(tag);
            if (forTag.matches()) {
                String iterable = forTag.group(2).trim();
                if (isLocalRooted(iterable, locals)) {
                    continue;
                }
                if (SIMPLE_NAME.matcher(iterable).matches()) {
                    merge(entries, iterable, TemplateVariableKind.COLLECTION);
                } else {
                    merge(entries, iterable, TemplateVariableKind.EXPRESSION);
                }
                continue;
            }
            Matcher ifTag = IF_TAG.matcher(tag);
            if (ifTag.matches()) {
                String condition = ifTag.group(1).trim();
                // Bare simple names only — complex conditions are not parsed.
                if (SIMPLE_NAME.matcher(condition).matches()
                        && !isLocalRooted(condition, locals)) {
                    merge(entries, condition, TemplateVariableKind.VARIABLE);
                }
            }
            // set / endfor / endif / else / other tags: nothing to surface.
        }
    }

    private static void addPlaceholder(String raw, Set<String> locals,
                                       Map<String, TemplateVariableKind> entries) {
        if (raw.isEmpty() || isLocalRooted(raw, locals)) {
            return;
        }
        merge(entries, raw, classify(raw));
    }

    private static boolean isLocalRooted(String token, Set<String> locals) {
        Matcher root = LEADING_NAME.matcher(token);
        return root.find() && locals.contains(root.group(1));
    }

    /**
     * First appearance fixes the position; {@code COLLECTION} upgrades a
     * plain {@code VARIABLE} sighting of the same name ({@code {{ items }}}
     * printed before its {@code {% for %}} header) — the richer knowledge
     * wins regardless of order.
     */
    private static void merge(Map<String, TemplateVariableKind> entries,
                              String name, TemplateVariableKind kind) {
        TemplateVariableKind existing = entries.get(name);
        if (existing == null) {
            entries.put(name, kind);
        } else if (existing == TemplateVariableKind.VARIABLE
                && kind == TemplateVariableKind.COLLECTION) {
            entries.put(name, TemplateVariableKind.COLLECTION);
        }
    }

    private static TemplateVariableKind classify(String raw) {
        PlaceholderKind parsed;
        try {
            PlaceholderToken token = PlaceholderUtils.parsePlaceholder(
                    StringConstant.PLACEHOLDER_PREFIX + raw + StringConstant.PLACEHOLDER_SUFFIX);
            parsed = token != null ? token.getKind() : PlaceholderKind.EXPRESSION;
        } catch (RuntimeException e) {
            // e.g. a reserved-prefix token with non-variable content — surface, don't drop
            parsed = PlaceholderKind.EXPRESSION;
        }
        // The framework pattern is ASCII-only, so a unicode simple name parses
        // as EXPRESSION even though both render paths treat it as a plain
        // variable — reclassify for input purposes (see SIMPLE_NAME).
        if (parsed == PlaceholderKind.EXPRESSION && SIMPLE_NAME.matcher(raw).matches()) {
            return TemplateVariableKind.VARIABLE;
        }
        return switch (parsed) {
            case VARIABLE -> TemplateVariableKind.VARIABLE;
            case RESERVED_FIELD -> TemplateVariableKind.RESERVED_FIELD;
            case EXPRESSION -> TemplateVariableKind.EXPRESSION;
        };
    }
}
