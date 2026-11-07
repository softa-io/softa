package io.softa.starter.message.mail.support;

import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.mail.enums.BodyMode;

/**
 * Body rules shared by acceptance (caller-supplied bodies) and the first delivery attempt
 * (template-rendered bodies).
 */
public final class MailBodies {

    private MailBodies() {}

    /**
     * The body mode to persist. A declared mode wins — it is how the template path carries author
     * intent (DERIVED vs AUTHORED plain text). Otherwise it is inferred from the bodies present,
     * AUTHORED when both are: a direct caller treats its plain text as canonical.
     */
    public static BodyMode modeOf(BodyMode declared, String html, String text) {
        if (declared != null) return declared;
        boolean hasHtml = StringUtils.hasText(html);
        boolean hasText = StringUtils.hasText(text);
        if (hasHtml && hasText) return BodyMode.HTML_WITH_AUTHORED_PLAIN;
        if (hasHtml) return BodyMode.HTML;
        return BodyMode.PLAIN;
    }

    /**
     * Application-level guard on body size. The columns are unbounded TEXT, so this configurable cap
     * is what stops runaway payloads (base64-inlined images, template loops).
     *
     * @throws BusinessException when either body is longer than {@code limit} characters
     */
    public static void requireWithinLimit(String html, String text, int limit) {
        int longest = Math.max(html == null ? 0 : html.length(), text == null ? 0 : text.length());
        if (longest > limit) {
            throw new BusinessException(
                    "Mail send rejected: the body exceeds the maximum length of {0} characters", limit);
        }
    }
}
