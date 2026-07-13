package io.softa.starter.message.mail.enums;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Primary content classification of a received email (mutually exclusive).
 * <p>
 * These values describe <em>what the email is</em> from a content perspective
 * — its purpose / role in the conversation. Orthogonal transport / wrapper
 * properties (mailing-list distribution, encryption, spam reputation) live
 * as separate boolean flags on {@code MailReceiveRecord} so that overlap
 * (e.g. an encrypted bounce, a spam calendar invite, a mailing-list
 * auto-reply) is preserved without information loss.
 * <p>
 * Only {@link #READ_RECEIPT} and {@link #BOUNCE} drive automated downstream
 * actions (linking back to the originating {@code MailSendRecord}). The other
 * values are recorded for observability — operators can dashboard on bounce
 * rates, auto-reply ratios, calendar volume, etc., without changing
 * receive-pipeline behavior.
 * <p>
 * To add a new content type: define a {@code @Component} implementing
 * {@link io.softa.starter.message.mail.classifier.MailClassificationRule} and
 * register it with an {@code @Order} value matching its detection precedence.
 * To add a new orthogonal flag: implement
 * {@link io.softa.starter.message.mail.classifier.MailFlagDetector} instead.
 */
@OptionSet
@Getter
@AllArgsConstructor
public enum ReceivedMailType {

    @OptionItem(description = "Regular correspondence — classifier ran cleanly with no specialty rule matching.")
    NORMAL("Normal"),
    @OptionItem(description = "MDN read receipt (RFC 8098). Triggers send-record receipt linkage.")
    READ_RECEIPT("ReadReceipt"),
    @OptionItem(description = "Delivery status notification / bounce / rejection (RFC 3464). Triggers send-record bounce linkage.")
    BOUNCE("Bounce"),
    @OptionItem(description = "Out-of-office or automated response (RFC 3834 Auto-Submitted header).")
    AUTO_REPLY("AutoReply"),
    @OptionItem(description = "Meeting invitation / iCalendar payload (RFC 5546 text/calendar).")
    CALENDAR_INVITE("CalendarInvite"),
    @OptionItem(description = "Classifier could not determine the type — at least one rule threw during scan. "
            + "An UNKNOWN spike usually signals classifier regression or malformed inbound mail.")
    UNKNOWN("Unknown");

    @JsonValue
    private final String code;

    /**
     * code map
     */
    private static final Map<String, ReceivedMailType> codeMap = Stream.of(values())
            .collect(Collectors.toMap(ReceivedMailType::getCode, Function.identity()));

    /**
     * Resolve a {@link ReceivedMailType} from its persisted {@code code}
     * value. Returns {@code null} when the input is null/blank or doesn't
     * match any known type — callers (e.g. legacy DB rows) get a graceful
     * null instead of an exception.
     */
    public static ReceivedMailType of(String code) {
        if (code == null) return null;
        return codeMap.get(code);
    }
}
