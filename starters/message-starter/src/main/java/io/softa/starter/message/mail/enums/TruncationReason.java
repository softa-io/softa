package io.softa.starter.message.mail.enums;

/**
 * Why an incoming email was processed in a degraded way.
 * Stored as the string value of {@link Enum#name()} on
 * {@code MailReceiveRecord.truncationReason}; null when fully processed.
 *
 * <p>Orthogonal to {@code mailType} — a bounce can also be truncated.
 */
public enum TruncationReason {

    /** {@code message.getSize()} exceeded {@code maxMessageSize}; body never fetched, envelope-only record persisted. */
    BodyTooLarge,

    /** At least one MIME part exceeded {@code maxAttachmentSize}; that attachment skipped, others uploaded. */
    AttachmentTooLarge,

    /** MIME nesting depth exceeded {@code maxMimeDepth}; body/attachment processing aborted. */
    MimeDepthExceeded,

    /** MIME part count exceeded {@code maxMimeParts}; body/attachment processing aborted. */
    MimePartsExceeded,

    /** JavaMail or downstream parsing threw an unexpected error; recorded for observability. */
    ParseFailed
}
