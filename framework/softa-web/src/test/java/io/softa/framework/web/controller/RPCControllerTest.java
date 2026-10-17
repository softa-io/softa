package io.softa.framework.web.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.base.utils.SerializeUtils;
import io.softa.framework.orm.config.RPCProperties;
import io.softa.framework.web.rpc.RPCRequestBody;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The model RPC endpoint authenticates the calling service before it touches the body. The body
 * used by the refusal cases is not even valid Base64: if a refused request ever reached
 * deserialization, the failure would be a decoding error instead of a permission error.
 */
class RPCControllerTest {

    private static final String UNREADABLE_BODY = "%%% not a payload %%%";

    private RPCController controller;
    private RPCProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RPCProperties();
        properties.setEnable(true);
        properties.setAccessKey("peer-key");
        properties.setSecretKey("peer-secret");
        controller = new RPCController();
        ReflectionTestUtils.setField(controller, "rpcProperties", properties);
    }

    private Object call(String key, String secret, String body) {
        return controller.handleRPCRequest("Employee", "noSuchMethod", key, secret, body);
    }

    @Test
    void refusesACallWithoutCredentials() {
        assertThatThrownBy(() -> call(null, null, UNREADABLE_BODY)).isInstanceOf(PermissionException.class);
    }

    @Test
    void refusesWrongCredentials() {
        assertThatThrownBy(() -> call("peer-key", "guess", UNREADABLE_BODY)).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> call("guess", "peer-secret", UNREADABLE_BODY)).isInstanceOf(PermissionException.class);
    }

    @Test
    void refusesEveryCallWhenCredentialsAreNotConfigured() {
        properties.setAccessKey(null);
        properties.setSecretKey("");
        assertThatThrownBy(() -> call(null, "", UNREADABLE_BODY)).isInstanceOf(PermissionException.class);
        assertThatThrownBy(() -> call("", "", UNREADABLE_BODY)).isInstanceOf(PermissionException.class);
    }

    @Test
    void refusesALoggedInUserEvenWithValidCredentials() {
        Context user = new Context();
        user.setTenantId(1L);
        user.setUserId(42L);
        assertThatThrownBy(() -> ContextHolder.callWith(user,
                () -> call("peer-key", "peer-secret", UNREADABLE_BODY)))
                .isInstanceOf(PermissionException.class);
    }

    @Test
    void anAuthenticatedPeerReachesMethodResolution() {
        RPCRequestBody body = new RPCRequestBody();
        body.setMethodArgs(new Object[]{"Employee"});
        body.setContext(new Context());
        String serialized = SerializeUtils.serialize(body);

        assertThatThrownBy(() -> call("peer-key", "peer-secret", serialized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }
}
