package io.softa.starter.user.controller;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import io.softa.framework.web.controller.ModelController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every generic model endpoint is either shadowed by {@link UserAccountController} or named here as
 * safe without one.
 *
 * <p>Spring resolves {@code /UserAccount/<x>} to this controller when it declares {@code <x>}, and
 * otherwise falls through to {@link ModelController}'s templated {@code /{modelName}/<x>} — which
 * applies no roster scope. The controller shadowed the list reads and one update and left the rest
 * to fall through, and that rest was the whole by-id surface: a tenant admin could delete, unmask or
 * bulk-rewrite a consultant's hidden membership through endpoints the User Accounts page never
 * called. The list was scoped; the row was not.
 *
 * <p>Pinned by enumeration rather than by a case per endpoint, because the failure mode is a
 * generic endpoint added NEXT — by someone who has never heard of consultants — falling through
 * unnoticed. This test names it.
 */
class UserAccountShadowsGenericEndpointsTest {

    /** Generic endpoints that need no shadow, each for a reason that does not depend on a row. */
    private static final Set<String> SAFE_WITHOUT_A_SHADOW = Set.of(
            // Model metadata only; reads no stored row.
            "GET /getDefaultValues",
            // Evaluates the payload it is given; reads no stored row.
            "POST /onChange/{fieldName}",
            // UserAccount is copyable = false: ModelServiceImpl refuses every copy API for it before a
            // row is touched. A membership is not a thing to duplicate.
            "GET /getCopyableFields",
            "POST /copyById",
            "POST /copyByIdAndFetch",
            "POST /copyByIds",
            "POST /copyByIdsAndFetch");

    /** {@code "VERB /path"} for every request mapping the class itself declares. */
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
    void everyGenericEndpointIsEitherShadowedOrNamedSafe() {
        Set<String> generic = mappingsOf(ModelController.class);
        Set<String> shadowed = mappingsOf(UserAccountController.class);

        Set<String> unguarded = new HashSet<>(generic);
        unguarded.removeAll(shadowed);
        unguarded.removeAll(SAFE_WITHOUT_A_SHADOW);

        assertThat(unguarded)
                .as("generic model endpoints reachable as /UserAccount/... without the roster scope — "
                        + "shadow each in UserAccountController, or name it in SAFE_WITHOUT_A_SHADOW with a reason")
                .isEmpty();
    }

    @Test
    void theSafeListNamesOnlyEndpointsThatStillExist() {
        // A stale entry would keep excusing an endpoint that has since changed shape.
        Set<String> generic = mappingsOf(ModelController.class);
        assertThat(generic).containsAll(SAFE_WITHOUT_A_SHADOW);
    }
}
