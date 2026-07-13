package io.softa.starter.message.mail.classifier;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.softa.starter.message.mail.support.MailClassification;

/**
 * Heuristic fallback: if the sender looks like a postmaster bot
 * ({@code mailer-daemon@...}, {@code postmaster@...}) treat the message
 * as a bounce even without a DSN attached; {@link MailClassificationSupport#heuristicBounce}
 * enriches the bounce info from body heuristics.
 * <p>
 * Runs after {@link DsnRule} because structured DSN always beats header
 * sniffing; runs before {@link KeywordRule} because a matching sender is
 * a stronger signal than subject-line words.
 */
@Component
@Order(30)
public class MailerDaemonRule implements MailClassificationRule {

    private static final String[] BOUNCE_SENDERS = {"mailer-daemon@", "postmaster@"};

    @Override
    public Optional<MailClassification> match(MimeMessage message) throws Exception {
        if (message.getFrom() == null || message.getFrom().length == 0) return Optional.empty();
        String from = ((InternetAddress) message.getFrom()[0]).getAddress();
        if (from == null) return Optional.empty();
        String fromLower = from.toLowerCase(Locale.ROOT);
        boolean matches = Arrays.stream(BOUNCE_SENDERS).anyMatch(fromLower::startsWith);
        if (!matches) return Optional.empty();

        return Optional.of(MailClassificationSupport.heuristicBounce(message));
    }
}
