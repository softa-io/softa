package io.softa.starter.message.mail.support;

import java.util.Properties;
import lombok.extern.slf4j.Slf4j;

import io.softa.starter.message.config.MessageProperties;

/**
 * The deployment-wide settings every Jakarta Mail session takes, sending or receiving: the transport
 * timeouts, under the protocol's own prefix, and the protocol debug log.
 * <p>
 * Jakarta Mail reads the timeouts as {@code mail.<protocol>.connectiontimeout} / {@code .timeout}: an
 * SMTPS or IMAPS session ignores the plain protocol's keys and, without its own, waits on the server
 * with no timeout at all. The read timeout also bounds the NOOP probe of a pooled SMTP connection.
 */
@Slf4j
public final class MailSessionSettings {

    private MailSessionSettings() {}

    /**
     * Put the timeouts and, when {@code softa.message.mail.debug} is on, {@code mail.debug} into
     * {@code props} for a session of {@code protocol} ({@code smtp}, {@code smtps}, {@code imaps}, …).
     *
     * @param server names the server in the debug warning
     */
    public static void apply(Properties props, String protocol, MessageProperties.Mail mail, Object server) {
        String prefix = "mail." + protocol + ".";
        props.put(prefix + "connectiontimeout", mail.getTransport().getConnectionTimeout().toMillis());
        props.put(prefix + "timeout", mail.getTransport().getReadTimeout().toMillis());
        if (mail.isDebug()) {
            // JavaMail writes the AUTH exchange to stdout, so this must stay off in production.
            props.put("mail.debug", "true");
            log.warn("mail.debug=true: JavaMail will dump the {} exchange, AUTH included, to stdout. "
                    + "Non-production only. server={}", protocol.toUpperCase(), server);
        }
    }
}
