package io.softa.starter.metadata.sequence.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.softa.framework.orm.sequence.exception.SequenceTemplateException;
import org.springframework.stereotype.Component;

/**
 * Renders sequence templates such as {@code EMP-{yyyy}-{seq:5}} into final
 * strings like {@code EMP-2026-00043}.
 *
 * <p>Token grammar:
 * <ul>
 *   <li>{@code {seq}} — number, no padding</li>
 *   <li>{@code {seq:N}} — number, zero-padded to N digits</li>
 *   <li>{@code {yyyy}} — 4-digit year</li>
 *   <li>{@code {yy}} — 2-digit year</li>
 *   <li>{@code {MM}} — 2-digit month</li>
 *   <li>{@code {dd}} — 2-digit day</li>
 * </ul>
 *
 * <p>Date tokens take their values from the supplied {@code anchorTime} (the
 * moment associated with the current reset key), not from
 * {@code Instant.now()}. This keeps two near-simultaneous {@code next()}
 * calls that span a midnight boundary self-consistent with the reset
 * decision.
 *
 * <p>Template validation (forbidden / required tokens, range checks) lives
 * at config save time; this renderer is best-effort and throws
 * {@link SequenceTemplateException} only on outright malformed input.
 */
@Component
public class TemplateRenderer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([a-zA-Z]+)(?::(\\d+))?\\}");

    private static final DateTimeFormatter FMT_YYYY = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter FMT_YY = DateTimeFormatter.ofPattern("yy");
    private static final DateTimeFormatter FMT_MM = DateTimeFormatter.ofPattern("MM");
    private static final DateTimeFormatter FMT_DD = DateTimeFormatter.ofPattern("dd");

    public String render(String template, long number, LocalDateTime anchorTime, String code) {
        if (template == null || template.isEmpty()) {
            throw new SequenceTemplateException(code, "Template is empty");
        }
        Matcher m = TOKEN_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String arg = m.group(2);
            String replacement = resolve(name, arg, number, anchorTime, code);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String resolve(String name, String arg, long number, LocalDateTime t, String code) {
        return switch (name) {
            case "seq" -> {
                if (arg == null) {
                    yield Long.toString(number);
                }
                int width = Integer.parseInt(arg);
                yield String.format("%0" + width + "d", number);
            }
            case "yyyy" -> t.format(FMT_YYYY);
            case "yy"   -> t.format(FMT_YY);
            case "MM"   -> t.format(FMT_MM);
            case "dd"   -> t.format(FMT_DD);
            default -> throw new SequenceTemplateException(code, "Unknown template token: {" + name + "}");
        };
    }
}
