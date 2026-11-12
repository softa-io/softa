package io.softa.starter.message.shared;

/**
 * Error codes and messages cut to the columns that hold them. An SMTP server's or a provider's message
 * has no length limit of its own, and a value longer than its column fails the very write that records
 * the failure: the record would stay in its last state instead of moving to RETRY or FAILED, and an
 * exhausted send would not reach the dead-letter table.
 */
public final class ErrorText {

    /** The {@code errorCode} columns of the send records. */
    public static final int CODE_LENGTH = 100;

    /** The {@code errorMessage} columns of the send records, and the dead letters' {@code lastErrorMsg}. */
    public static final int MESSAGE_LENGTH = 1000;

    private ErrorText() {}

    /** {@code text} cut to {@code max} characters, an ellipsis marking the cut; null stays null. */
    public static String clip(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int end = max - 1;
        // Keep a surrogate pair whole rather than leave half a character before the ellipsis.
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }
}
