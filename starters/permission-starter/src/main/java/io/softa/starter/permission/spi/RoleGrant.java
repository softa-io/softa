package io.softa.starter.permission.spi;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * What one role grants, kept apart from the user's other roles.
 *
 * <p>A role is a grant pair: these actions, on these rows, with these sensitive fields. Merging the
 * three parts of every role into three unions — the shape {@link PermissionInfo} carried alone until
 * now — lets one role's rows meet another role's actions and fields: a role that may view everyone
 * plus a role that may edit one department used to add up to editing everyone. Kept per role, an
 * action reaches the rows of the roles holding that action, and a sensitive field the rows of the
 * roles granting it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "One role's grant pair: its actions, row scope and sensitive fields")
public class RoleGrant implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "Role id")
    private Long roleId;

    @Schema(description = "Role code; null for a tenant-authored role")
    private String roleCode;

    @Schema(description = "Role name")
    private String roleName;

    @Schema(description = "Permission ids this role grants")
    private Set<String> permissions;

    @Schema(description = "Model → this role's scope rules, OR-combined")
    private Map<String, List<ScopeRule>> modelScopeMap;

    @Schema(description = "Model → a condition AND-ed onto this role's scope rules for that model (Filters JSON)")
    private Map<String, JsonNode> modelScopeConditions;

    @Schema(description = "Model → sensitive field set ids this role grants, keyed by the set's own model")
    private Map<String, Set<String>> modelSensitiveFieldSetsMap;

    /**
     * The legal entities this role reaches, tri-state as on {@link PermissionInfo#getGrantedCompanyIds()}:
     * {@code null} unrestricted, empty none, otherwise exactly those.
     */
    @Schema(description = "Legal entities this role reaches; null = unrestricted")
    private Set<Long> grantedCompanyIds;
}
