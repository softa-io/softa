package io.softa.starter.message.mail.support;

import java.util.Properties;
import org.junit.jupiter.api.Test;

import io.softa.starter.message.config.MessageProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Every Jakarta Mail session — the SMTP send and the IMAP / POP3 fetch alike — takes its timeouts under its
 * own protocol's prefix, and the debug switch covers them all: it used to reach the send session only.
 */
class MailSessionSettingsTest {

    @Test
    void theTimeoutsGoUnderTheSessionsOwnProtocol() {
        Properties props = new Properties();

        MailSessionSettings.apply(props, "imaps", new MessageProperties().getMail(), 7L);

        assertEquals(5000L, props.get("mail.imaps.connectiontimeout"));
        assertEquals(30000L, props.get("mail.imaps.timeout"));
        // An IMAPS session ignores the plain protocol's keys.
        assertNull(props.get("mail.imap.timeout"));
        assertNull(props.get("mail.debug"));
    }

    @Test
    void theDebugSwitchReachesFetchSessionsToo() {
        MessageProperties.Mail mail = new MessageProperties().getMail();
        mail.setDebug(true);
        Properties props = new Properties();

        MailSessionSettings.apply(props, "pop3s", mail, 7L);

        assertEquals("true", props.get("mail.debug"));
    }
}
