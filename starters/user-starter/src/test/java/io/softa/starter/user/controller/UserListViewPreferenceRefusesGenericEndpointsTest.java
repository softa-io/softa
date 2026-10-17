package io.softa.starter.user.controller;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.web.controller.ModelController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every generic model endpoint is declared, and refused, by {@link UserListViewPreferenceController}.
 *
 * <p>A path the controller does not declare falls through to {@link ModelController}'s templated
 * {@code /{modelName}/...}, which serves any person's arrangement to an admin's permission bypass.
 * Pinned by enumeration, so a generic endpoint added later fails here instead of reopening that.
 */
class UserListViewPreferenceRefusesGenericEndpointsTest {

    private static Set<String> mappingsOf(Class<?> controller) {
        Set<String> out = new TreeSet<>();
        for (Method m : controller.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            RequestMethod[] verbs = mapping.method().length == 0
                    ? new RequestMethod[] {RequestMethod.GET} : mapping.method();
            for (String path : mapping.path()) {
                for (RequestMethod verb : verbs) {
                    out.add(verb.name() + " " + path);
                }
            }
        }
        return out;
    }

    @Test
    void everyGenericEndpointIsDeclaredHere() {
        Set<String> unguarded = new HashSet<>(mappingsOf(ModelController.class));
        unguarded.removeAll(mappingsOf(UserListViewPreferenceController.class));

        assertThat(unguarded)
                .as("generic model endpoints reachable as /UserListViewPreference/... — refuse each in the controller")
                .isEmpty();
    }

    @Test
    void theRefusalsRefuse() {
        UserListViewPreferenceController controller = new UserListViewPreferenceController(null);
        assertThatThrownBy(controller::refuseGenericRead).isInstanceOf(PermissionException.class);
        assertThatThrownBy(controller::refuseGenericWrite).isInstanceOf(PermissionException.class);
    }
}
