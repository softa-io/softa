package io.softa.starter.user.dto;

import java.util.List;
import lombok.Data;

/**
 * One row of the Consultant Profiles list (PRD §2.2).
 *
 * <p>A DTO rather than generic model columns because two of the five are out of reach of either:
 * the login identifiers live on {@code UserIdentity}, a satellite whose FK points AT the profile, so
 * no forward cascade path leads to them; and Authorized Tenants is computed — only the grants
 * covering today, resolved to names. Serving the row assembled is also what keeps the identifiers
 * where they belong: they sit apart from the browsable person record on purpose, and a cascade that
 * dragged them onto every directory read would undo that separation.
 */
@Data
public class ConsultantRowDTO {

    /** The person — what every other call about this consultant is keyed on. */
    private Long profileId;

    /** PRD's "Username" column. The person's own display name; no separate consultant name exists. */
    private String username;

    private String email;

    private String mobile;

    /** Enabled / Disabled. Disabled stops every company at once without touching the grants. */
    private Boolean active;

    /**
     * Companies whose authorization covers today, for the row's badges.
     *
     * <p>Only the live ones, matching what the consultant's own tenant switcher will offer — a list
     * that showed lapsed grants here would disagree with the screen the consultant sees. The
     * platform's edit form asks separately and does show them, because that is the row an operator
     * extends.
     */
    private List<Tenant> authorizedTenants;

    /** A company badge: the name if the directory can supply one, always the id behind it. */
    @Data
    public static class Tenant {
        private Long tenantId;
        private String tenantName;
    }
}
