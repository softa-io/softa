package io.softa.starter.user.service;

import io.softa.starter.user.dto.MyAccountSummary;

/**
 * The signed-in person changing their own password or login mobile, proving it is them with a code
 * sent to the login mobile (or, for the mobile, their password).
 *
 * <p>Each step hands back a ticket the next step spends. A ticket names one step, one person and
 * ten minutes; it is useless for any other step, to anyone else, or later. All of it acts on the
 * PERSON: the password and the login mobile are the same in every company, and every other device
 * of theirs, in every company, is signed out. The device the change was made from stays signed in.
 */
public interface MyCredentialService {

    /** The account half of the "Me" header. */
    MyAccountSummary summary();

    /** Send a code to the person's login mobile. */
    void sendCurrentMobileCode();

    /**
     * Check the code sent to the login mobile.
     *
     * @return a ticket for {@link #changePassword}
     */
    String verifyCodeForPassword(String code);

    /** Set a new password with the ticket {@link #verifyCodeForPassword} handed out. */
    void changePassword(String ticket, String newPassword);

    /**
     * Prove it is the person before changing the login mobile: the code sent to the current login
     * mobile, or the password — which counts toward the same lock as signing in, and cannot be used
     * while that lock holds.
     *
     * @param method {@code code} or {@code password}
     * @return a ticket for {@link #checkNewMobile}
     */
    String verifyCurrentForMobile(String method, String code, String password);

    /**
     * Name the new login mobile and send it a code. Refused when it is the current one, or another
     * person's login mobile — unless that person has left every company, whose number is released
     * when the change is confirmed.
     *
     * @return a ticket for {@link #resendNewMobileCode} and {@link #confirmNewMobile}
     */
    String checkNewMobile(String ticket, String newMobile);

    /** Send the new number another code; the ticket stays the same. */
    void resendNewMobileCode(String ticket);

    /** Check the code sent to the new number and make it the person's login mobile. */
    void confirmNewMobile(String ticket, String code);
}
