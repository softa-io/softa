package io.softa.starter.user.entity;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.base.enums.Language;
import io.softa.framework.base.enums.Timezone;
import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.annotation.Index;
import io.softa.framework.orm.annotation.Model;
import io.softa.framework.orm.entity.AuditableModel;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IdStrategy;
import io.softa.starter.user.enums.Gender;
import io.softa.starter.user.enums.UserLayoutDensity;

/**
 * UserProfile Model
 */
@Data
@EqualsAndHashCode(callSuper = true)
/**
 * The PERSON. One row per human being, shared across every company they work for.
 *
 * <p>Deliberately NOT multi-tenant: a person is not owned by a company. {@link UserAccount} is the
 * per-company membership; {@link UserIdentity} is the person's credentials (a 1:1 satellite),
 * separated so this model can carry a browsable directory surface without dragging a password hash
 * onto it.
 */
@Model(
        idStrategy = IdStrategy.DISTRIBUTED_LONG,
        searchName = {"fullName"}
)
public class UserProfile extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Field(label = "ID")
    private Long id;

    /**
     * DEPRECATED back-reference. {@link UserAccount#getProfileId()} is the relation now; this is
     * kept only so the data migration can map the old 1:1 pairing, and so a rolled-back binary
     * still finds its rows. Not required any more — a person may exist before any membership does.
     */
    @Field(label = "User ID")
    private Long userId;

    // Login credentials — loginEmail / loginMobile / password / passwordSalt / passwordLockedUntil —
    // now live on UserIdentity, a 1:1 satellite. Personal information stays here; a password hash
    // must not ride along with a browsable directory of names and photos.

    /** As long as a full name may be anywhere else (128): it is copied in when an account is minted. */
    @Field(length = 128)
    private String fullName;

    @Field
    private String chineseName;

    @Field
    private LocalDate birthDate;

    @Field
    private LocalTime birthTime;

    @Field
    private String birthCity;

    @Field
    private Gender gender;

    @Field(label = "Profile Photo File ID", fieldType = FieldType.FILE)
    private Long photoId;

    @Field
    private Language language;

    @Field
    private Timezone timezone;

    @Field
    private UserLayoutDensity density;
}
