package io.softa.starter.message.mail.enums;

import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;

/**
 * Mutually-exclusive body shape for an email — captures the original (or
 * intended) MIME structure plus, for {@code multipart/alternative}, where
 * the {@code text/plain} part came from.
 *
 * <p>Persisted on {@code MailTemplate}, {@code MailSendRecord}, and
 * {@code MailReceiveRecord} as the enum {@link Enum#name()}. One vocabulary
 * across all three tables lets the UI pick a renderer without caring whether
 * the value was authored, sent, or received.
 *
 * <p>Storage strategy across all three tables: two columns
 * ({@code bodyHtml}, {@code bodyText}), both nullable. Whichever combination
 * is populated is consistent with this mode:
 * <ul>
 *   <li>{@link #HTML} — {@code bodyHtml} only.</li>
 *   <li>{@link #PLAIN} — {@code bodyText} only.</li>
 *   <li>{@link #HTML_WITH_DERIVED_PLAIN} — both populated; the plain part
 *       was machine-derived from the HTML at send time.</li>
 *   <li>{@link #HTML_WITH_AUTHORED_PLAIN} — both populated; the plain part
 *       was hand-authored independently of the HTML.</li>
 * </ul>
 *
 * <p>The {@code DERIVED} / {@code AUTHORED} split is meaningful for audit and
 * deliverability analysis — e.g. "show me all sends whose plain text was
 * reviewed by a human". Receive-side records use {@code HTML_WITH_AUTHORED_PLAIN}
 * for incoming {@code multipart/alternative} because the sender's plain part
 * is treated as canonical content; we never label it {@code DERIVED}.
 */
@OptionSet
public enum BodyMode {

    /** Single {@code text/html} part; {@code bodyHtml} populated, {@code bodyText} null. */
    @OptionItem(label = "HTML")
    HTML,

    /** Single {@code text/plain} part; {@code bodyText} populated, {@code bodyHtml} null. */
    PLAIN,

    /**
     * {@code multipart/alternative} carrying both parts; the plain part was
     * derived from the HTML via {@code HtmlUtils.toText} at send time
     * (machine-generated, not human-reviewed).
     */
    @OptionItem(label = "HTML With Derived Plain")
    HTML_WITH_DERIVED_PLAIN,

    /**
     * {@code multipart/alternative} carrying both parts; the plain part was
     * authored independently (by a template author or API caller). On the
     * receive side, this is the value used for any incoming
     * {@code multipart/alternative} since the sender's plain part is taken
     * as canonical.
     */
    @OptionItem(label = "HTML With Authored Plain")
    HTML_WITH_AUTHORED_PLAIN
}
