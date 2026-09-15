package io.softa.starter.user.dto;

import java.time.LocalDate;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * What the platform's Consultant Profile form saves.
 *
 * <p>One payload for create and edit, because the form is one form: basic details plus the whole
 * authorization table. The table is applied as a SET, not as add/remove calls — see
 * {@code ConsultantService.replaceAuthorizations} for why.
 *
 * <p><b>Why this is not an entity.</b> One save spans FOUR tables, and no entity is the shape of
 * it: the username belongs to {@link io.softa.starter.user.entity.UserProfile}, the email and
 * mobile to {@link io.softa.starter.user.entity.UserIdentity}, the Enabled switch to
 * {@link io.softa.starter.user.entity.ConsultantProfile}, and the grant rows to
 * {@link io.softa.starter.user.entity.ConsultantAuthorization}. Taking
 * {@code ConsultantProfile} as the parameter would carry exactly one of the five fields the
 * screen edits.
 *
 * <p>Nor can the generic model surface bridge it. A cascade write reaches a satellite whose FK
 * points AT the row being saved; {@code UserIdentity}'s FK points at the PROFILE, so there is no
 * forward path from the consultant record to the identifiers — which is deliberate, since those
 * identifiers are kept off the browsable person record on purpose.
 *
 * <p>And splitting it into four calls is not available either: the four writes have to land or fail
 * together. A grant saved without its minted membership, or a person created without the consultant
 * record, is a half-authorized consultant that the form cannot show and an operator cannot repair —
 * which is why {@code ConsultantService.save} is {@code @Transactional} over the whole payload.
 */
@Data
public class ConsultantProfileDTO {

    /**
     * The person, when this edits an existing one.
     *
     * <p>Also how a consultant is made out of somebody who ALREADY exists — an employee of some
     * client company being brought onto the implementation team. Login identifiers are globally
     * unique, so that person cannot be given a second profile with the same address; they are the
     * same person wearing another hat, which is exactly what the model represents. Null creates a
     * new person.
     */
    private Long profileId;

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Please enter a valid email address")
    private String email;

    @NotBlank(message = "Mobile is required")
    private String mobile;

    /** Defaults to enabled on create — a consultant is made in order to be used. */
    private Boolean active;

    @Valid
    private List<AuthorizationRow> authorizations;

    /** One row of the Authorized Tenants table. */
    @Data
    public static class AuthorizationRow {

        @NotNull(message = "Every authorization needs a tenant, a start date and an end date")
        private Long tenantId;

        @NotNull(message = "Every authorization needs a tenant, a start date and an end date")
        private LocalDate startDate;

        @NotNull(message = "Every authorization needs a tenant, a start date and an end date")
        private LocalDate endDate;
    }
}
