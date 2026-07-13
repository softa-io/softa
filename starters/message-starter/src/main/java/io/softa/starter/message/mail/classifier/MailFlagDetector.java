package io.softa.starter.message.mail.classifier;

import jakarta.mail.internet.MimeMessage;

import io.softa.starter.message.mail.support.MailClassification;

/**
 * Sets one orthogonal boolean flag on the {@link MailClassification} (e.g.
 * mailing-list distribution, encryption, spam reputation). Flags may apply
 * on top of any primary {@code ReceivedMailType}, so detectors run in
 * parallel after the primary type chain has resolved — none short-circuits
 * the others.
 * <p>
 * Implementations should be side-effect-free with respect to the message
 * itself and idempotent on the classification (calling {@code apply} twice
 * leaves the same flag set). Exceptions are caught and logged by
 * {@link io.softa.starter.message.mail.support.MailClassifier}; a thrown
 * detector simply does not contribute its flag.
 */
public interface MailFlagDetector {

    /**
     * Inspect {@code message} and, if the relevant signal is present, set
     * the corresponding flag on {@code classification} via its setter.
     */
    void apply(MimeMessage message, MailClassification classification) throws Exception;
}
