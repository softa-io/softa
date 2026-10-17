package io.softa.starter.user.service;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.validation.WriteContext;

/**
 * {@code Role.code} decides the admin bypass on its own, so no request may set or change it — on any
 * write path, which is why the rule sits at the write root rather than on a controller.
 */
class RoleCodeWriteValidatorTest {

    private final RoleCodeWriteValidator validator = new RoleCodeWriteValidator();

    @Test
    void appliesToRoleOnly() {
        assertThat(validator.supports("Role")).isTrue();
        assertThat(validator.supports("UserRoleRel")).isFalse();
    }

    @Test
    void refusesPuttingAnAdminCodeOnAnOrdinaryRole() {
        WriteContext ctx = update(row("code", "SUPER_ADMIN"), stored(null));

        validator.validateUpdate(ctx);

        assertThat(ctx.errors()).singleElement().satisfies(e -> assertThat(e.field()).isEqualTo("code"));
    }

    @Test
    void refusesClearingOrReplacingABuiltInCode() {
        WriteContext cleared = update(row("code", null), stored("EMPLOYEE"));
        WriteContext replaced = update(row("code", "TENANT_ADMIN"), stored("EMPLOYEE"));

        validator.validateUpdate(cleared);
        validator.validateUpdate(replaced);

        assertThat(cleared.hasErrors()).isTrue();
        assertThat(replaced.hasErrors()).isTrue();
    }

    @Test
    void letsAnUpdateThatEchoesOrOmitsTheCodeThrough() {
        WriteContext omitted = update(row("name", "Payroll Clerk"), stored(null));
        WriteContext echoedNull = update(row("code", null), stored(null));
        WriteContext echoedBlank = update(row("code", " "), stored(null));

        validator.validateUpdate(omitted);
        validator.validateUpdate(echoedNull);
        validator.validateUpdate(echoedBlank);

        assertThat(omitted.hasErrors()).isFalse();
        assertThat(echoedNull.hasErrors()).isFalse();
        assertThat(echoedBlank.hasErrors()).isFalse();
    }

    @Test
    void refusesACodeOnCreate() {
        WriteContext ctx = WriteContext.of("Role", AccessType.CREATE, row("code", "TENANT_ADMIN"), null);

        validator.validateCreate(ctx);

        assertThat(ctx.hasErrors()).isTrue();
    }

    @Test
    void letsSystemPathsWriteTheCode() {
        // Seeding creates the built-in roles this way; a request never carries the flag.
        Context context = new Context();
        context.setSkipPermissionCheck(true);
        WriteContext create = WriteContext.of("Role", AccessType.CREATE, row("code", "TENANT_ADMIN"), null);
        WriteContext change = update(row("code", "TENANT_ADMIN"), stored(null));

        ContextHolder.runWith(context, () -> {
            validator.validateCreate(create);
            validator.validateUpdate(change);
        });

        assertThat(create.hasErrors()).isFalse();
        assertThat(change.hasErrors()).isFalse();
    }

    private static WriteContext update(Map<String, Object> patch, Map<String, Object> original) {
        patch.put("id", 7L);
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        return WriteContext.of("Role", AccessType.UPDATE, merged, patch, original);
    }

    private static Map<String, Object> stored(String code) {
        Map<String, Object> row = row("id", 7L);
        row.put("code", code);
        return row;
    }

    private static Map<String, Object> row(String field, Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put(field, value);
        return row;
    }
}
