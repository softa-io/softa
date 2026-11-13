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

    /**
     * The departments this role reaches, as the {@code idPath} of each granted root: that department and
     * everything under it. Tri-state like {@link #grantedCompanyIds}: {@code null} unrestricted (the role has
     * no department grant, or grants all departments), empty none, otherwise the subtrees under those paths.
     * Paths rather than the subtrees' ids, so a department created or moved under a granted root is in the
     * grant at once — see {@code DefaultPermissionSnapshotProvider.readGrantedDepartmentPaths}.
     *
     * <p>Bounds, per role, every model carrying {@code departmentId} as a to-one onto the department
     * model, and the department model itself by its own {@code idPath}.
     */
    @Schema(description = "Paths of the departments this role reaches, each with its subtree; null = unrestricted")
    private Set<String> grantedDepartmentPaths;

    /**
     * What the department grant adds for whoever holds the role, beside {@link #grantedDepartmentPaths}: any of
     * {@code OWN_DEPARTMENT}, {@code MANAGED_DEPARTMENTS} and {@code DIRECT_REPORTS}. Kept by name rather than
     * resolved, because it differs per holder and changes with them; the data plane reads it against the
     * request's employee. Only read where the grant is configured, so {@link #grantedDepartmentPaths} is not
     * {@code null}. A record has to meet any one of the two.
     */
    @Schema(description = "Conditions of the department grant worked out for the holder")
    private Set<String> departmentConditions;

    /**
     * Models whose records this role views in every department: reading them is not bounded by the department
     * grant. Changing them still is.
     */
    @Schema(description = "Models this role views regardless of its department grant")
    private Set<String> departmentExemptModels;
}
