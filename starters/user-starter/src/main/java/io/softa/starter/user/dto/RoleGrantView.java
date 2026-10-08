package io.softa.starter.user.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * One role's grant pair as the effective-permissions panel shows it: what the role lets its holder
 * do, on which rows, with which sensitive fields — on its own, not merged with the user's other roles.
 *
 * <p>The same shape as the engine's per-role grant in its snapshot, so a cached snapshot maps onto it
 * field by field. Scope rules and conditions stay raw JSON for the reason {@link EffectivePermissionsView}
 * gives: the engine's types live in a module this one does not depend on.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoleGrantView {

    private Long roleId;

    private String roleCode;

    private String roleName;

    /** Permission ids this role grants. */
    private Set<String> permissions;

    /** Model → this role's OR-combined scope rules, raw {@code {scopeType, scopeExpr?}} JSON. */
    private Map<String, List<JsonNode>> modelScopeMap;

    /** Model → the condition AND-ed onto this role's rules for that model, Filters JSON. */
    private Map<String, JsonNode> modelScopeConditions;

    /** Model → sensitive field set ids this role grants, keyed by the set's own model. */
    private Map<String, Set<String>> modelSensitiveFieldSetsMap;
}
