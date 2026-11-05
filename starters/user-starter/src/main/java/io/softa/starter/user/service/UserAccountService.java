package io.softa.starter.user.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import jakarta.validation.constraints.NotNull;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.orm.service.EntityService;
import io.softa.starter.user.dto.UserAccountDTO;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.dto.WorkContacts;
import io.softa.starter.user.entity.UserAccount;

/**
 * UserAccount Model Service Interface
 */
public interface UserAccountService extends EntityService<UserAccount, Long> {

    /**
     * Every membership belonging to one person, across all tenants.
     *
     * <p>{@code @CrossTenant} by nature: it is asked after authenticating but before a company is
     * chosen, so there is no tenant context to scope it to — that is the whole point of the call.
     *
     * <p>Off-boarded memberships are excluded: a former employee must not see their previous
     * company in the picker.
     */
    List<UserAccount> listMembershipsOf(Long profileId);

    /**
     * Get user by email
     *
     * @param email email
     * @return UserAccount
     */
    Optional<UserAccount> getUserByEmail(String email);

    /**
     * Every account of this person, in every tenant and every status — Deactivated included. For
     * reaching everything that hangs off the person's accounts (sessions, push devices), not for
     * deciding where they may go.
     */
    List<Long> listAccountIdsOf(Long profileId);

    /**
     * The people whose JOINED accounts (Active / Frozen; Locked is derived and stored as Active) carry
     * this enterprise email, in any tenant. Email login identifies the person this way: an email on a
     * Pending, Invited or Deactivated account signs nobody in. Compared case-insensitively.
     */
    java.util.Set<Long> findJoinedProfilesByEmail(String email);

    /**
     * Take a login mobile back from a DORMANT person — one with no account left that is not
     * Deactivated — so the number can be used by someone else (a carrier recycles numbers; the
     * leaver must not be "found" by them). Written to the security log.
     *
     * @param mobile   the number about to be used
     * @param scenario what is using it (join, Reset User, change login mobile), for the log
     * @return whether a dormant holder was released; false when nobody holds it, or the holder is
     *         still active somewhere and keeps it
     */
    boolean releaseDormantLoginMobile(String mobile, String scenario);

    /**
     * Whether this number is held as a login mobile only by a dormant person — one
     * {@link #releaseDormantLoginMobile} would release. Asks, writes nothing.
     */
    boolean isDormantLoginMobile(String mobile);

    /**
     * Whether this enterprise email sits on an account (any tenant, any status but Deactivated)
     * that belongs to a person other than {@code profileId}. One person may use one address in
     * several companies; two people may not. The account {@code exceptAccountId} never counts —
     * it is the one being joined or edited.
     */
    boolean isEmailHeldByAnotherPerson(String email, Long profileId, Long exceptAccountId);

    /**
     * Put the person's login mobile on every account they have joined, so the account list shows and
     * finds the number they sign in with. Accounts not joined yet keep their invitation mobile, and
     * closed ones the number they closed with.
     */
    void followLoginMobile(Long profileId);

    /**
     * Get user by mobile
     *
     * @param mobile mobile
     * @return UserAccount
     */
    Optional<UserAccount> getUserByMobile(String mobile);

    /**
     * Register a new user
     *
     * @param accountInfo User account information
     * @param profileInfo User profile information
     * @return UserInfo
     */
    UserInfo registerNewUser(@NotNull UserAccountDTO accountInfo, @NotNull UserProfileDTO profileInfo);


    /**
     * Register an account for someone who has not been contacted yet — an
     * {@link io.softa.starter.user.enums.AccountStatus#PENDING} account with NO password.
     * Sending the invitation is a separate, explicit action
     * ({@code UserInvitationService.invite}) which flips the account to
     * {@link io.softa.starter.user.enums.AccountStatus#INVITED}; accepting the link then
     * sets the password and activates it.
     *
     * @param email    Email (used as the username when present)
     * @param mobile   Mobile (used as the username when email is absent)
     * @param fullName Display name for the account (nickname) and profile; when blank, falls
     *                 back to the login identifier (email or mobile)
     * @return UserInfo
     */
    UserInfo registerInvitedUser(String email, String mobile, String fullName);

    /**
     * The reason a new account with these contacts must be refused in the current tenant, or
     * {@code null} when it may be created (possibly by linking to an existing person or reviving
     * their closed membership). Same rules {@link #registerInvitedUser} applies — one source, so an
     * import pre-check and the create path cannot disagree.
     */
    String newAccountRefusal(String email, String mobile);

    /**
     * Off-board a membership: close it and strip what must not outlive it.
     *
     * <p>Three things happen together because leaving any one of them out is a real defect:
     *
     * <ol>
     *   <li><b>status → DEACTIVATED</b> — the membership is over;</li>
     *   <li><b>role grants cleared</b> — a closed membership carrying live grants is a standing
     *       hole, and because a re-hire REVIVES this same row, grants left behind would be
     *       silently inherited by the returning employee;</li>
     *   <li><b>the work email released from the person's login identifiers</b> — otherwise, once
     *       the address is recycled, a new hire holding it could verify by code straight into the
     *       previous holder's personal account.</li>
     * </ol>
     *
     * <p>Idempotent: off-boarding an already-off-boarded membership changes nothing.
     *
     * @param accountId the membership to close
     */
    void offBoard(Long accountId);

    /**
     * Re-hire: reopen a closed membership as {@code PENDING}, for its own person and with the work
     * contacts it already carries. Sends nothing — HR follows with the ordinary Send Invitation.
     *
     * <p>An explicit action rather than something the create path infers from a contact. A closed
     * row still holding an address says only who held the address LAST: work emails and pool phones
     * are reissued, so treating the row as "this is the person being created" would revive the
     * leaver's membership under a newcomer's details and surface the leaver's identity to whoever
     * typed them. Naming the row by id states the intent instead of guessing it.
     *
     * @throws io.softa.framework.base.exception.BusinessException unless the row is DEACTIVATED
     */
    void rehire(Long accountId);

        /**
     * Whether this contact is used as the work email/mobile of more than one account.
     *
     * <p>A shared work number is ordinary as a CONTACT — a shop's phone, a shared floor handset, a
     * manager's number entered for a worker who has none. What it cannot be is a way to IDENTIFY a
     * person: a verification code sent there proves control of the number, not of any one holder.
     * The /join flow asks this before it will resolve an invitee from an identifier, because
     * resolving a shared contact to whichever person happens to hold it already is exactly how one
     * employee signs in as another.
     */
    boolean isWorkContactShared(String contact);

    /**
     * This person's membership of one company, WHATEVER its status — deactivated included.
     *
     * <p>Distinct from {@link #listMembershipsOf}, which hides deactivated rows: those rows still
     * occupy the {@code (tenantId, profileId)} unique slot, so a caller about to bind a person to a
     * company has to see a closed prior membership that {@code listMembershipsOf} would not report.
     * At most one row exists, since the slot is unique.
     */
    Optional<UserAccount> findMembershipInTenant(Long tenantId, Long profileId);

    /**
     * Another account in the CURRENT company holding this work contact, if any.
     *
     * <p>Scoped to one tenant because that is the scope of the rule: work contacts are unique per
     * company ({@code uk_user_account_tenant_email}), not globally. One person working at two
     * tenants legitimately carries the same work email in both, and the global form of this check
     * is what used to make that impossible — refusing the second company's account, and then
     * refusing to let HR edit or re-hire it.
     *
     * <p>What IS globally unique is the LOGIN identifier on {@code UserIdentity}, guarded separately
     * (see {@code isIdentifierClaimable} and {@code assertContactNotShared}): a shared work contact
     * simply never becomes a login route, rather than being banned.
     *
     * @param contact work email or work mobile; null/blank finds nothing
     * @param exceptAccountId the account being written, excluded from the search; may be null
     */
    Optional<UserAccount> findContactHolderInTenant(String contact, Long exceptAccountId);

    /**
     * The work contacts this membership's EMPLOYEE RECORD currently holds.
     *
     * <p>The record is the single source of contact details, so the two operations that move them —
     * Reset User and Unbind &amp; Re-invite — read them from here rather than from their caller, and
     * their dialogs echo these read-only. To change a number, HR edits the record and comes back;
     * that is what makes "the account follows the record" true without a second writer.
     *
     * <p>Read generically by model name, the way {@code UserAccessController} and the framework's
     * {@code EmployeeContextEnricher} already do — no corehr dependency, and
     * {@link io.softa.framework.orm.meta.ModelManager#existModel} makes it a no-op in a deployment
     * with no {@code Employee} model at all.
     *
     * <p>The ACCOUNT's own contacts are deliberately not consulted: they still hold the value being
     * replaced, which is what Reset User notifies. Reading the new value from the record and the old
     * one from the account is the whole reason both are available at once.
     *
     * @return the record's contacts, or {@link WorkContacts#none()} when there is no record
     */
    WorkContacts archiveWorkContacts(Long userId);

    /**
     * {@link #archiveWorkContacts} with the personal mobile on the record and the current login
     * mobile of the account's person: what the Reset User and Unbind &amp; Re-invite dialogs show.
     */
    WorkContacts archiveContactsForAccount(Long userId);

    /**
     * The personal mobile on the employee record behind this account — what the invitation is sent
     * to and what Reset User makes the person's login mobile. Null when there is no record or none.
     */
    String archivePersonalMobile(Long userId);

    /**
     * Why this enterprise email cannot go on this account, or null when it can: another account of
     * the same company holds it, or a different person holds it anywhere (Deactivated aside). Asked
     * before the employee record is saved, so the record and the account cannot disagree.
     */
    String enterpriseEmailRefusal(Long accountId, String email);

    /**
     * The employee record's Work Email changed: carry it onto the account, whatever its status.
     * Refused with {@code EMAIL_TAKEN} on the same grounds as {@link #enterpriseEmailRefusal}. A
     * joined account (Active / Locked / Frozen) also mails the OLD address and texts the login
     * mobile, and the change is written to the security log.
     */
    void syncEnterpriseEmail(Long accountId, String email);

    /**
     * The employee record's personal mobile changed: before the person has joined (Pending /
     * Invited) the invitation mobile follows it; after that the login mobile is the person's own and
     * this does nothing.
     */
    void syncInviteMobile(Long accountId, String mobile);

    /**
     * Prepare a membership for someone re-joining this company, reusing the closed row.
     *
     * <p>Re-hire revives rather than inserts, because {@code (tenantId, profileId)} is unique —
     * one person has at most one membership per company, which is what lets the database enforce
     * it instead of application code racing with itself. The row is reset to a fresh
     * {@code PENDING} (new work contacts, no activation, no roles) so nothing from the previous
     * stint leaks into the new one; employment history lives on the employee record, which IS
     * created anew.
     *
     * @return the revived membership, or empty when this person has no closed membership here
     */
    java.util.Optional<UserAccount> reviveMembership(Long profileId, String workEmail, String workMobile);

    /**
     * Reopen one closed membership, named by its own id, for a re-hire: the same reset as
     * {@link #reviveMembership}. Found by id because a row reopened once and closed again before
     * anyone joined has no person to be found by.
     *
     * @return the revived membership, or empty when the account is not closed
     */
    java.util.Optional<UserAccount> reviveAccount(Long accountId, String workEmail, String workMobile);

    /**
     * Change the current user's password
     *
     * @param currentPassword Current password
     * @param newPassword New password
     */
    void changeMyPassword(String currentPassword, String newPassword);

    /**
     * Sets a FIRST password for the logged-in person — the actionable half of
     * {@code AuthenticationResult.mustSetPassword}.
     *
     * <p>Separate from {@link #changeMyPassword} because that method demands the current password,
     * which is precisely what these people do not have: they arrived by invitation or verification
     * code, so requiring it would make the forced step impossible to complete.
     *
     * <p>Refuses when a password already exists. Without that check this would be an
     * authenticated password reset with no proof of the old one — any hijacked session could
     * silently take the account over.
     */
    void setMyFirstPassword(String newPassword);

    /**
     * Whether the logged-in person still owes a first password.
     *
     * <p>Exists so the forced Set Password step survives a page reload. Authentication reports
     * {@code mustSetPassword} once, in its response; a client that only remembers it from there
     * loses the requirement the moment the person refreshes or closes the tab — and since a
     * password-less person cannot come back through the password route, skipping it that way locks
     * them out of their own account rather than merely postponing a screen.
     */
    boolean mustSetMyPassword();

    /**
     * Whether {@link #setMyFirstPassword} would be accepted for the logged-in person — that is,
     * whether they have no password yet.
     *
     * <p>A different question from {@link #mustSetMyPassword}, and the difference is the whole
     * reason this exists. That one asks whether somebody is FORCED to set a password before going
     * any further, and it deliberately answers no for a consultant: code login is their intended
     * way in and nobody asked them to create a credential. A screen that OFFERS to set one has to
     * ask this instead — keyed on the forcing rule, it showed the exempt consultant the
     * change-password form, which demands a current password they have never had, leaving the one
     * person the exemption was written for with no way to ever get a password.
     *
     * <p>False for a person with no credentials row at all: the call would refuse them, and
     * offering a form that cannot succeed is the failure this is here to prevent.
     */
    boolean canSetMyFirstPassword();

    /**
     * Force reset user password (admin operation)
     *
     * @param userId User ID
     * @param newPassword New password
     * @return Success status
     */
    boolean forceResetPassword(Long userId, String newPassword);

    /**
     * Suspend a membership: the person keeps their credentials, this company keeps their record,
     * and they cannot get in until it is lifted.
     *
     * <p>Replaces Lock: a MANUAL lock and the automatic password lockout were two mechanisms
     * for two different things wearing one name. The lockout is a reaction to guessing, lives on
     * the credential, and expires by itself; freezing is an administrator's decision about this
     * membership, and only an administrator lifts it.
     *
     * <p>Freezing a LOCKED account clears the password lock on the way through. Otherwise lifting
     * the freeze would hand back an account the lockout still refuses, and the administrator who
     * lifted it has no way to see why.
     *
     * <p>Idempotent: freezing an already-frozen membership changes nothing.
     */
    void freezeAccount(Long userId, String reason);

    /**
     * Lift a suspension, returning the membership to ACTIVE.
     *
     * <p>Only from FROZEN. Refusing on anything else is deliberate: silently "unfreezing" an
     * INVITED or DEACTIVATED account would drag it into a state its own flow never reaches.
     */
    void unfreezeAccount(Long userId, String reason);

    void unfreezeAccounts(List<Long> userIds, String reason);

    /**
     * Reset a membership's work contacts, keeping the person.
     *
     * <p>The everyday case Unbind & Re-invite is too big a hammer for: the RIGHT person holds this
     * membership, their email or phone simply changed. So the person stays attached, their password
     * is untouched, and no invitation is issued — they keep signing in exactly as before.
     *
     * <p>What has to move with the contact is the LOGIN identifier. It was seeded from the work
     * contact, so leaving it behind means the person keeps signing in with an address this company
     * no longer recognises, while the recycled one becomes a route into their account for whoever
     * receives it next. Only the identifier THIS company issued is rewritten — a personal login
     * email is not ours to change, which is why it compares before writing.
     *
     * <p>The OLD address is notified, not the new one: if this reset was not the person's own doing,
     * the message reaches where they can still read it. Telling only the new address would inform
     * whoever now holds it.
     */
    void resetWorkContacts(Long userId, String reason);

    /**
     * Writes account rows through {@code write}, carrying any change of email or mobile to the
     * person's login identifiers in the same transaction.
     *
     * <p>A contact edited on the account alone left the person's login identifier behind: the old
     * address still signed them in, and still named them — a new account created with it was
     * refused as "already a member". The identifier moves only where it was the account's own
     * value, the same rule {@link #resetWorkContacts} applies, so a personal login email is never
     * rewritten.
     *
     * <p>Only before the membership is accepted (PENDING / INVITED): nobody has signed in with these
     * contacts yet, so there is nobody to warn. Afterwards the change goes through
     * {@link #resetWorkContacts}, which notifies the old address, and an edit here is refused.
     *
     * @param rows  the rows about to be written, each carrying its {@code id}
     * @param write the write itself
     * @return what {@code write} returned
     */
    <T> T updateCarryingLoginIdentifiers(List<Map<String, Object>> rows, Supplier<T> write);
}