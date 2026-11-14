package io.softa.starter.user.dto;

import java.util.List;

import io.softa.starter.user.dto.NavConfigOptions.SfsRef;

/**
 * Per-MODEL config options for the role wizard's data step (step 2). Unlike
 * {@link NavConfigOptions} (which is keyed per navigation), this is keyed per
 * model and covers BOTH the primary models of the granted navigations AND the
 * related/lookup models reachable from them via relational fields — because
 * those related models get queried at runtime (lookup / cascade) and therefore
 * need their own {@code role_data_scope} row, or they fail-closed to 0 rows.
 *
 * @param model                       model name (PascalCase)
 * @param label                       human label (MetaModel.labelName, falls back to model)
 * @param applicableScopes            scope-type names applicable to this model (column-shape derived)
 * @param applicableSensitiveFieldSets SFS owned by / attached to this model
 * @param related                     false = primary model of a granted nav; true = derived lookup model
 * @param departmentBound             whether the role's department grant bounds this model's records
 */
public record RoleModelConfigOption(
        String model,
        String label,
        List<String> applicableScopes,
        List<SfsRef> applicableSensitiveFieldSets,
        boolean related,
        boolean departmentBound) {
}
