package io.softa.starter.user.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /userAccess/userEffectivePermissions} response — one user's effective access, as the
 * runtime would enforce it, for the admin panel on the account detail page.
 *
 * <p>Normalizes the TWO sources the endpoint can answer from so the payload has ONE shape either
 * way. A cache hit deserializes the engine's serialized {@code PermissionInfo} JSON straight into
 * this class; a miss maps a freshly built {@link UiContext} plus its scope rules into it. Without
 * that normalization the response would change shape depending on whether the subject happened to be
 * active recently, which is exactly the kind of drift the frontend cannot code against.
 *
 * <p>{@link JsonIgnoreProperties}{@code (ignoreUnknown = true)} is what lets the cache-hit path work:
 * {@code PermissionInfo} additionally carries {@code permissionCodes} and {@code grantedCompanyIds},
 * neither of which this view exposes. Declared on the class rather than relied upon from the global
 * Jackson config (which does disable {@code FAIL_ON_UNKNOWN_PROPERTIES}) because the unit-test
 * mapper leaves that check ON.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EffectivePermissionsView {

    /** SUPER_ADMIN holders bypass every check, so the four grant sets below are empty by design and
     *  the UI shows a full-access notice instead of an empty panel. Derived from {@code roleCodes}. */
    private boolean superAdmin;

    /** Role codes the user holds — the contributing roles, for display. */
    private Set<String> roleCodes;

    /** Navigation ids visible to the user (leaf grants plus expanded ancestors). */
    private Set<String> navigations;

    /** Permission ids granted, unioned across the user's roles. */
    private Set<String> permissions;

    /** Model → OR-combined row-scope rules, raw {@code {scopeType, scopeExpr?}} JSON. Raw nodes
     *  rather than the engine's {@code ScopeRule}: that type lives in permission-starter, which
     *  user-starter must not depend on, and the wire shape is identical. */
    private Map<String, List<JsonNode>> modelScopeMap;

    /** Model → granted sensitive-field-set ids, keyed by the set's canonical model. */
    private Map<String, Set<String>> modelSensitiveFieldSetsMap;

    /**
     * Each role on its own — the actions, rows and sensitive fields it grants. This, not the unions
     * above, is how the data plane decides: an action reaches the rows of the roles holding it, and a
     * sensitive field shows on the rows of the roles granting it. Each role applies on its own; results
     * add up. Empty for the admin principals, which bypass the data plane.
     */
    private List<RoleGrantView> roleGrants;

    /** {@code cache} when answered from the engine's snapshot, {@code rebuilt} when computed here.
     *  Diagnostic only — the frontend ignores it, but it is the difference between "this is what the
     *  runtime is enforcing right now" and "this is what it would enforce", which is worth being able
     *  to tell apart when a grant looks wrong. */
    private String source;
}
