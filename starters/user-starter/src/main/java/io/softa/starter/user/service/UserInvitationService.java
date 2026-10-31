package io.softa.starter.user.service;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.dto.InvitationInfo;
import io.softa.starter.user.dto.JoinContacts;
import io.softa.starter.user.dto.JoinEntry;
import io.softa.starter.user.entity.UserInvitation;

/**
 * Issues + accepts password-set-by-email tokens (invitations + self-service resets). Generates a
 * high-entropy one-time token, stores only its SHA-256 hash, emails the raw token by publishing a
 * framework {@code MailRequestMessage} (delivered by message-starter when present), and on accept sets the password
 * (activating an INVITED account). Extends {@link EntityService} so the 明细 (records) are browsable
 * through the generic model APIs.
 */
public interface UserInvitationService extends EntityService<UserInvitation, Long> {

    /**
     * Revoke the outstanding invitation: kill the live link and put the account back to
     * {@link io.softa.starter.user.enums.AccountStatus#PENDING}, so it reads as "created,
     * not contacted" again and can be invited afresh.
     *
     * <p>Refuses once the account has a password: at that point the person has already
     * joined, and revoking would strand a working account in a pre-activation state.
     * Undoing THAT is off-boarding, not revocation.
     *
     * @param userId the account whose invitation is being withdrawn
     */
    void revokeInvitation(Long userId);

    /**
     * Invalidate every invitation link of this account that has not been used yet, leaving the
     * account's status alone. For an account being closed: its links must stop working with it.
     */
    void revokeOutstandingLinks(Long userId);

    /**
     * The /join entry check — whether this token may proceed to identity verification, and if not,
     * why (five conditions, asked in order).
     *
     * <p>Public: called before any session exists, by whoever opened the link.
     */
    JoinEntry inspectJoinToken(String rawToken);

    /**
     * Resolves the PLAINTEXT address the invitation names, for the caller to send a code to.
     *
     * <p>Exists because the join page is shown MASKED contacts by design (a leaked link must not
     * hand out a working phone number), so it cannot call the plaintext send-code endpoints with an
     * address of its own. Making the invitee re-type their address instead would both worsen the
     * flow and turn the link into an address oracle for whoever holds it.
     *
     * <p>Server-internal: the returned value must never reach a response body. It re-runs the full
     * entry gate, because the invitation may have been revoked or re-sent since the page loaded and
     * the caller is about to send a message.
     *
     * @param channel {@code "email"} or {@code "mobile"}
     */
    String resolveJoinChannel(String rawToken, String channel);

    /**
     * Both PLAINTEXT addresses the invitation names, for callers that must compare rather than send.
     *
     * <p>Server-internal, same reasoning as {@link #resolveJoinChannel}. Also re-runs the entry
     * gate: every caller is about to act on the invitation's behalf.
     */
    JoinContacts resolveJoinContacts(String rawToken);

    /**
     * The membership a usable invitation is for — so /join can see whether the account already
     * belongs to a person before it goes looking for one by address.
     *
     * <p>Exists for the re-hired leaver: their revived row still carries their profileId, while
     * the address on the invitation resolves to nobody (off-boarding released it from their
     * identity). Find-or-create by address would then mint a second person and confirmJoin would
     * hand the row to it, orphaning the real one — password, other-company memberships and all.
     * The row already knows who it is for; this is how the flow asks it.
     *
     * <p>Server-internal, same reasoning as {@link #resolveJoinChannel}. Empty when the token is
     * not usable — callers that already passed the gate treat that as a race, not as "unbound".
     */
    java.util.Optional<io.softa.starter.user.entity.UserAccount> resolveJoinAccount(String rawToken);

    /**
     * Confirm joining: bind the verified person to the membership and activate it.
     *
     * <p>Separate from setting a password because they mean different things — see the
     * implementation for why activation waits for this call.
     *
     * @param proof the value verifyJoinCode returned; required to name this invitation and this
     *              person, and spent here on success
     */
    void confirmJoin(String rawToken, Long profileId, String proof);

    /**
     * Whether {@code profileId} is only the placeholder person created together with this account —
     * no password, nothing else joined — whom /join may replace with the person it verified.
     */
    boolean isPlaceholderPerson(Long profileId, Long accountId);

    /**
     * Issue (or re-issue) an INVITE token for a user and email the set-password link. Any prior
     * PENDING token for the user is revoked so only the newest link works.
     *
     * @param userId    the account to invite (must have an email)
     * @param invitedBy the inviter's userId (null if system-initiated)
     */
    void invite(Long userId, Long invitedBy);

    /**
     * Unbind a membership from the person it was wrongly bound to, and re-invite it.
     *
     * <p>The remedy for a mis-binding: an invitation went to the wrong address, someone else
     * accepted it, and the membership now belongs to a person who should never have had it.
     * Editing the work contact does not undo that — the person is already attached, and their
     * login identifiers may still carry an address this company issued.
     *
     * <p>Four things happen together, and leaving any one out is a defect:
     *
     * <ol>
     *   <li><b>the work contacts are released from the OLD person's login identifiers</b> — the
     *       security half: otherwise the wrong person keeps a working login route into this
     *       company's address, and a verification code to it still reaches them;</li>
     *   <li><b>the membership is detached</b> ({@code profileId} and {@code activationTime}
     *       cleared) — it is nobody's until the right person accepts;</li>
     *   <li><b>the new work contacts are recorded</b>, refusing an address another account already
     *       holds rather than surfacing a constraint violation;</li>
     *   <li><b>a fresh REINVITE token is issued</b> and every outstanding one revoked, so the link
     *       the wrong person may still be holding stops working.</li>
     * </ol>
     *
     * <p>Role grants are deliberately KEPT: they were assigned to the position, and the position is
     * what this row represents. Off-boarding clears them because the position itself is closing;
     * unbinding is the opposite — the position stands and only its holder was wrong.
     *
     * @param reason required, at most 500 characters. Mandatory because this is the one operation
     *               that detaches a person from a membership that keeps its authority, and
     *               "who did this and why" is the only thing a later review has to go on
     */
    void unbindAndReinvite(Long userId, String reason, Long operatedBy);

    /**
     * Self-service forgot-password: issue a PASSWORD_RESET token for the email. Silently no-ops when
     * the email is unknown (no account enumeration).
     */
    void forgotPassword(String email);

    /**
     * Set the password via a token (invite accept OR reset). Validates the token (PENDING +
     * unexpired), sets a fresh salted hash, activates an INVITED account, and marks the token
     * ACCEPTED. Public entry point — throws a generic {@code BusinessException} on any invalid token.
     */
    void acceptToken(String rawToken, String newPassword);

    /** Validate a token for the public set-password page (returns validity + email, no leak of why). */
    InvitationInfo inspectToken(String rawToken);
}
