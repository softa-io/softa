package io.softa.starter.user.entity;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.annotation.Field;
import io.softa.framework.orm.enums.OnDelete;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A role's grant rows go with it.
 *
 * <p>Asserted on the annotation because that is where the decision is made: {@code AnnotationParser}
 * copies {@code onDelete} into {@code sys_field}, and {@code ModelServiceImpl.deleteByIds} deletes the
 * referrers it names. Without it, deleting a role left its navigation, data-scope and sensitive-field
 * rows behind, pointing at a role that no longer exists. Its assignments are not among them: a role
 * someone holds is refused deletion instead ({@code RoleServiceImpl}).
 */
class RoleGrantRowsCascadeTest {

    @Test
    void deletingARoleDeletesItsGrantRows() throws NoSuchFieldException {
        for (Class<?> grant : List.of(RoleNavigation.class, RoleDataScope.class, RoleSensitiveFieldSet.class)) {
            Field roleId = grant.getDeclaredField("roleId").getAnnotation(Field.class);
            assertThat(roleId.onDelete()).as(grant.getSimpleName()).containsExactly(OnDelete.CASCADE);
        }
    }

    @Test
    void anAssignmentIsNotDeletedWithItsRole() throws NoSuchFieldException {
        Field roleId = UserRoleRel.class.getDeclaredField("roleId").getAnnotation(Field.class);
        assertThat(roleId.onDelete()).isEmpty();
    }
}
