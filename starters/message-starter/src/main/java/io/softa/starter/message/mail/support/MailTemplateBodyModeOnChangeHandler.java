package io.softa.starter.message.mail.support;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.utils.HtmlUtils;
import io.softa.framework.web.dto.OnChangeResponse;
import io.softa.framework.web.onchange.FieldOnChangeHandler;
import io.softa.framework.web.onchange.OnChangeContext;
import io.softa.starter.message.mail.enums.BodyMode;

/**
 * Editor linkage for {@code MailTemplate.bodyMode} (and, through
 * {@link PlatformMailTemplateBodyModeOnChangeHandler}, the platform's template
 * editor): when the author switches
 * mode, migrate the existing body into the editor that the new mode actually
 * sends, and clear the column the new mode ignores — so the form state (and
 * therefore the saved row) always matches the {@link BodyMode} storage
 * contract ("whichever combination is populated is consistent with this mode").
 *
 * <p>Prefill rules — a non-empty target is never overwritten:
 * <ul>
 * <li>→ {@code PLAIN} / {@code HTML_WITH_AUTHORED_PLAIN}: empty {@code bodyText}
 * is drafted from {@code bodyHtml} via {@link HtmlUtils#toText} — the same
 * converter the send path uses for {@code HTML_WITH_DERIVED_PLAIN}, so the
 * draft the author reviews is exactly what machine derivation would send.</li>
 * <li>→ {@code HTML} / {@code HTML_WITH_DERIVED_PLAIN} /
 * {@code HTML_WITH_AUTHORED_PLAIN}: empty {@code bodyHtml} is drafted from
 * {@code bodyText} as escaped paragraphs, giving the author a formatting
 * starting point instead of a blank rich-text editor.</li>
 * </ul>
 *
 * <p>Clearing (returning {@code null} for a field) only touches form state —
 * nothing persists until the author saves, and Cancel restores the loaded
 * record — so a mode switch is explorable and reversible before save.
 */
@Component
public class MailTemplateBodyModeOnChangeHandler implements FieldOnChangeHandler {

    @Override
    public String model() {
        return "MailTemplate";
    }

    @Override
    public Set<String> fields() {
        return Set.of("bodyMode");
    }

    @Override
    public OnChangeResponse onChange(OnChangeContext context) {
        BodyMode mode = parseMode(context.value());
        if (mode == null) {
            return null;
        }
        String html = asText(context.values().get("bodyHtml"));
        String text = asText(context.values().get("bodyText"));
        Map<String, Object> patch = new HashMap<>();
        switch (mode) {
            case PLAIN -> {
                if (!StringUtils.hasText(text) && StringUtils.hasText(html)) {
                    patch.put("bodyText", HtmlUtils.toText(html));
                }
                if (StringUtils.hasText(html)) {
                    patch.put("bodyHtml", null);
                }
            }
            case HTML, HTML_WITH_DERIVED_PLAIN -> {
                if (!StringUtils.hasText(html) && StringUtils.hasText(text)) {
                    patch.put("bodyHtml", textToHtml(text));
                }
                if (StringUtils.hasText(text)) {
                    patch.put("bodyText", null);
                }
            }
            case HTML_WITH_AUTHORED_PLAIN -> {
                if (!StringUtils.hasText(text) && StringUtils.hasText(html)) {
                    patch.put("bodyText", HtmlUtils.toText(html));
                } else if (!StringUtils.hasText(html) && StringUtils.hasText(text)) {
                    patch.put("bodyHtml", textToHtml(text));
                }
            }
        }
        return patch.isEmpty() ? null : OnChangeResponse.builder().values(patch).build();
    }

    private static BodyMode parseMode(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return BodyMode.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String asText(Object value) {
        return value != null ? value.toString() : null;
    }

    /**
     * Plain text → minimal semantic HTML: escape, blank-line-separated blocks
     * become {@code <p>}, single newlines become {@code <br>}. Deliberately
     * unstyled — it is a formatting starting point, not a rendering promise.
     */
    private static String textToHtml(String text) {
        String escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        return Arrays.stream(escaped.split("\\R{2,}"))
                .map(paragraph -> "<p>" + paragraph.replaceAll("\\R", "<br>") + "</p>")
                .collect(Collectors.joining());
    }
}
