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
 * <p><b>Why the dates and not a status column.</b> The grant expires by the calendar, with nobody
 * pressing anything: {@code start <= today <= end} is evaluated when access is used, so expiry needs
 * no job and no flip, and extending it is one date edit. Reusing {@link io.softa.starter.user.enums.AccountStatus}
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

    @Field(required = true, label = "Authorization Start",
            description = "First day the grant admits, inclusive")
    private LocalDate startDate;

    @Field(required = true, label = "Authorization End",
            description = "Last day the grant admits, inclusive. Past this date access stops on its "
                    + "own — extending it is editing this date, not re-creating the grant")
    private LocalDate endDate;
}
