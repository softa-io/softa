package io.softa.starter.user.dto;

/**
 * Request and response bodies of the {@code /me/password/**} and {@code /me/mobile/**} endpoints: the signed-in
 * person changing their own password or login mobile, one proved step at a time.
 */
public final class MeCredentialRequests {

    private MeCredentialRequests() {
    }

    /** A verification code. */
    public record CodeBody(String verificationCode) {
    }

    /** The ticket the step before handed out. */
    public record TicketBody(String ticket) {
    }

    /** A new password, with the ticket from verifying the code. */
    public record ChangePasswordBody(String ticket, String newPassword) {
    }

    /**
     * Proof it is the person: {@code method} is {@code code} or {@code password}, with {@code verificationCode}
     * or {@code password} filled in.
     */
    public record VerifyCurrentBody(String method, String verificationCode, String password) {
    }

    /** The new login mobile, with its country code. */
    public record CheckNewMobileBody(String ticket, String newMobile) {
    }

    /** The code sent to the new number, with the ticket from naming it. */
    public record ConfirmMobileBody(String ticket, String verificationCode) {
    }

    /** What a step hands to the next one. */
    public record TicketView(String ticket) {
    }
}
