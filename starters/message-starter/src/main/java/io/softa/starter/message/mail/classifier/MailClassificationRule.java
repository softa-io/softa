package io.softa.starter.message.mail.classifier;

import java.util.Optional;
import jakarta.mail.internet.MimeMessage;

import io.softa.starter.message.mail.support.MailClassification;

/**
 * One step in the mail-classification chain. Implementations inspect an
 * incoming {@link MimeMessage} and, if the message matches their criterion,
 * return a populated {@link MailClassification}; otherwise they return
 * {@link Optional#empty()} so the chain moves on.
 * <p>
 * Order is set via Spring's {@link org.springframework.core.annotation.Order}
 * annotation — lower values are tried first. Custom deployments add their own
 * rules simply by registering a new {@code @Component} implementing this
 * interface (e.g. provider-specific NDR shapes from Exchange / Google).
 */
public interface MailClassificationRule {

    /**
     * Inspect {@code message}. Return a classification if this rule matches;
     * empty if the next rule should try.
     * <p>
     * Implementations should be side-effect-free and tolerate malformed
     * messages — an exception thrown here will be logged by the chain and
     * treated as a no-match.
     */
    Optional<MailClassification> match(MimeMessage message) throws Exception;
}
