package io.softa.starter.sentry;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MDC entries named {@code tag.<name>} become Sentry tags, so an ERROR line logged with them can be
 * filtered by them; other MDC entries stay out of the tags.
 */
class SentryContextEnricherTest {

    private final SentryContextEnricher enricher = new SentryContextEnricher();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void tagPrefixedMdcEntriesBecomeTags() {
        MDC.put("tag.message_channel", "sms");
        MDC.put("tag.error_code", "REJECTED_SOURCE");
        MDC.put("tag.empty", "");
        MDC.put("tag.", "no name");
        MDC.put("traceId", "t-1");

        SentryEvent event = enricher.process(new SentryEvent(), new Hint());

        assertThat(event.getTags())
                .containsEntry("message_channel", "sms")
                .containsEntry("error_code", "REJECTED_SOURCE")
                .doesNotContainKeys("empty", "", "traceId", "tag.message_channel");
    }

    @Test
    void noMdcLeavesTheEventAlone() {
        SentryEvent event = enricher.process(new SentryEvent(), new Hint());

        assertThat(event.getTags()).isNullOrEmpty();
    }
}
