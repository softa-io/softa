package io.softa.starter.user.entity;

import java.io.Serial;
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
 * Marks a {@link UserProfile} as platform consultant staff, and carries the one switch that applies
 * to all of their access at once.
 *
 * <p>A satellite rather than columns on {@link UserProfile}, for the reason {@link UserIdentity} is
 * one: the person is a shared, browsable record, and "works for ZingKey implementation" is a
 * platform fact about them that no tenant should be reading off the person. It also keeps a
 * consultant addressable before they have any grant — a profile with no authorization still exists
 * and still lists (the PRD's "Authorized Tenants: —" row), which a grants-only design could not
 * represent.
 *
 * <p>The same person may be an employee somewhere and a consultant here. That is one
 * {@code UserProfile} with employment {@link UserAccount}s and consultant ones side by side — not
 * two people. Login identifiers stay globally unique on {@link UserIdentity}, so there is exactly
 * one person behind an address however many hats they wear.
 *
 * <p><b>Why {@code active} is a plain field and not {@code activeControl}.</b> Framework active
 * control would gate every query on this model, which sounds like free enforcement — but the tenant
 * audit log has to name a consultant as the actor of what they did months ago, including one who has
 * since been disabled. Auto-filtering would make those rows resolve to nobody and silently drop the
 * Consultant label off history. Access is instead decided in one place, where the check is visible
 * and testable.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG
)
@Index(indexName = "uk_consultant_profile_profile", fields = {"profileId"}, unique = true,
        message = "This person is already a consultant.")
public class ConsultantProfile extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    /**
     * The person. One row per consultant, {@code CASCADE} because this record says nothing on its
     * own — without the person there is no consultant to describe.
     */
    @Field(fieldType = FieldType.ONE_TO_ONE, relatedModel = UserProfile.class, required = true,
            onDelete = OnDelete.CASCADE)
    private Long profileId;

    @Field(required = true, label = "Status",
            description = "Enabled / Disabled. Disabled stops entry to EVERY authorized tenant at "
                    + "once, without touching the grants — so re-enabling restores exactly the "
                    + "access that was there, with each grant's own dates still deciding")
    private Boolean active;
}
