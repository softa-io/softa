package io.softa.starter.user.entity;

import java.io.Serial;
import java.time.LocalDate;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.framework.orm.enums.OnDelete;
import io.softa.starter.user.enums.AccountStatus;

/**
 * One consultant's permission to work inside one company, for a bounded period.
 *
 * <p>A consultant is platform staff (implementation / support), not anybody's employee. They are an
 * ordinary {@link UserProfile} — the same global person every employee is — and the platform grants
 * them access to a tenant by adding a row here. Saving the grant is what mints the matching
 * {@link UserAccount}; this row is what decides whether that account may be entered.
 *
 * <p><b>Deliberately NOT multi-tenant.</b> The row is written and read by the platform, and it names
 * the tenant it grants — a tenant-scoped model would make the platform's own list invisible to
 * itself and let a tenant see who was authorized into it, which is a platform matter.
 *
 * <p><b>Why a date and not a status column.</b> The grant expires by the calendar, with nobody
 * pressing anything: {@code today <= end} is evaluated when access is used, so expiry needs no job
 * and no flip, and extending it is one date edit. An empty end date is an open-ended grant. Reusing {@link io.softa.starter.user.enums.AccountStatus}
 * for "authorization ended" would collide head-on with off-boarding, which owns DEACTIVATED and whose
 * {@code rehire} refuses anything else — a consultant whose grant lapsed would look like a leaver.
 *
 * <p>Revoking a grant deletes the row; the {@code UserAccount} it minted stays. That is on purpose:
 * the account is what the tenant's audit log points at, so deleting it would blank the record of what
 * the consultant did while they had access.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG
)
// One grant per (consultant, tenant). A second row for the same pair would make "is this grant live
// today?" ambiguous — two rows could disagree — so the question has exactly one answer by
// construction. Extending access is editing the dates on the row, never adding another.
@Index(indexName = "uk_consultant_auth_profile_tenant", fields = {"profileId", "tenantId"},
        unique = true, message = "This consultant is already authorized for that company.")
public class ConsultantAuthorization extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    /**
     * The consultant. {@code CASCADE}: the grants exist only to say where this person may work, so
     * deleting the person takes them with it rather than leaving grants pointing at nobody.
     */
    @Field(fieldType = FieldType.MANY_TO_ONE, relatedModel = UserProfile.class, required = true,
            onDelete = OnDelete.CASCADE,
            description = "The consultant's person record — the same UserProfile every employee has")
    private Long profileId;

    @Field(required = true,
            description = "The company this grant admits the consultant to. A plain id, not a "
                    + "relation: tenant directory rows live outside this starter, and a consultant "
                    + "grant must survive being read without one")
    private Long tenantId;

    /**
     * The membership this grant minted, which is what the consultant actually signs in as.
     *
     * <p>The pair is also derivable from {@code (profileId, tenantId)} — both models are unique on
     * it — and it used to be resolved that way, by reading the memberships and matching them up in
     * memory. The relation says the same thing to the database, so a grant can be read together with
     * the state of its account instead of beside it.
     *
     * <p><b>No {@code onDelete}.</b> Revoking deletes the grant and deliberately keeps the account —
     * the tenant's audit log points at it — so a cascade here would destroy exactly what the revoke
     * rules were written to preserve. Re-authorizing points a new grant back at the same row.
     *
     * <p>Null is a real state: a grant whose membership was never minted. The form shows it, because
     * an authorization that created nothing is the one row worth looking at.
     */
    @Field(fieldType = FieldType.ONE_TO_ONE, relatedModel = UserAccount.class,
            description = "The membership this grant minted — the account the consultant signs in as")
    private Long accountId;

    /**
     * That membership's status, joined at query time rather than stored.
     *
     * <p>Entry needs the platform's grant AND the customer's own account to agree, and only the first
     * is editable from the platform's screen. {@code dynamic} so it takes no column: a copy would be
     * a second answer to a question the account already answers, and the two would drift the first
     * time a tenant suspended somebody.
     */
    @Field(cascadedField = "accountId.status", dynamic = true,
            description = "The minted membership's status — the customer's half of whether this "
                    + "grant admits today")
    private AccountStatus accountStatus;

    /**
     * Last day the grant admits, inclusive — or <b>null for open-ended</b>.
     *
     * <p>There is deliberately no start date. A grant is made in order to be used, so it admits from
     * the moment it is saved; a start date only ever said "not yet", which nobody had a use for and
     * which turned every authorization into two fields to get right instead of one.
     *
     * <p>Optional because the common case is an engagement with no agreed end, and requiring a date
     * there bought nothing but a recurring errand: somebody has to notice the lapse and re-authorize,
     * and until they do the consultant is locked out of work they are supposed to be doing. An
     * open-ended grant is ended the way it was made — by an operator deciding so.
     *
     * <p>Past this date access stops on its own, with nobody pressing anything. Extending is editing
     * this date, never re-creating the grant.
     */
    @Field(label = "Authorization End",
            description = "Last day the grant admits, inclusive. Empty means open-ended. Past this "
                    + "date access stops on its own — extending it is editing this date, not "
                    + "re-creating the grant")
    private LocalDate endDate;
}
