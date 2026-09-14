package io.softa.starter.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.softa.starter.user.enums.AccountStatus;

/**
 * One company a person belongs to, offered as a login target.
 *
 * <p>Authentication proves WHO you are ({@code UserProfile}); this says WHERE you can go. The two
 * are separate steps now because a person can belong to several tenants, and the session must
 * carry one membership — {@code accountId} is what the session ends up holding.
 *
 * @param accountId  the membership to log into (this becomes the session's userId)
 * @param tenantId   the company
 * @param tenantName display name for the picker
 * @param status     shown as a badge; a non-ACTIVE option is listed but not selectable, so the
 *                   person can see that the company exists and why they cannot enter (frozen /
 *                   locked) rather than facing a list that silently omits it
 * @param locked     whether the PERSON's password login is currently locked (PRD D5). A property
 *                   of the credential, so it is the same on every option of one picker. Shown as
 *                   a badge, never enforced: PRD §1.5 greys a Locked company, but the picker is
 *                   reached only after authentication, and §1.6 / D5 keep code login open during
 *                   a lock — greying here would refuse a person the code route just admitted.
 * @param unavailableReason why this row cannot be entered despite the membership being fine —
 *                  today only a company the platform has frozen. Null when nothing blocks
 *                  it. Its own field rather than a reading of {@code status}, because the account
 *                  status here is genuinely Active: it is the COMPANY that is unavailable, and
 *                  showing "Active" beside a row that refuses entry explains nothing. Deliberately
 *                  one neutral phrase and not the tenant's actual state — a consultant has no
 *                  business learning a customer's billing standing.
 * @param consultant whether this is a CONSULTANT's access rather than an employment. The picker
 *                   labels it, because the person needs to know which hat they are putting on: an
 *                   employment persists, a consultancy is a grant with an end date. Only LIVE grants
 *                   reach here — a lapsed one is absent, not listed-and-greyed like a frozen
 *                   employment, because it is not access they hold rather than access on hold.
 */
public record MembershipOption(Long accountId, Long tenantId, String tenantName, AccountStatus status,
                               boolean locked, boolean consultant, String unavailableReason) {

    /**
     * Whether this option can actually be entered.
     *
     * <p>{@code @JsonProperty} is required, not decoration: the name is neither a record component
     * nor a {@code getX}/{@code isX} getter, so Jackson does not discover it and the field simply
     * never reaches the client — where every option then reads as unselectable and the picker
     * refuses every company it just listed.
     */
    @JsonProperty("selectable")
    public boolean selectable() {
        // Deliberately independent of locked — see the component doc.
        return status == AccountStatus.ACTIVE && unavailableReason == null;
    }
}
