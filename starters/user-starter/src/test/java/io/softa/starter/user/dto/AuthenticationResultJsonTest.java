package io.softa.starter.user.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.UserInfo;
import io.softa.starter.user.enums.AccountStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client branches on {@code resolved} to decide between "session issued" and "show the
 * picker". That name is part of the wire contract, so it is asserted here rather than left to the
 * {@code isX} bean convention that happens to produce it today — and the annotation itself is
 * asserted, since the convention makes the wire assertion alone pass without it.
 */
class AuthenticationResultJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aResolvedResult_saysResolvedOnTheWire() throws Exception {
        String json = mapper.writeValueAsString(
                AuthenticationResult.resolved(7L, new UserInfo(), false));

        assertThat(json).contains("\"resolved\":true").contains("\"signInRequired\":false");
    }

    @Test
    void signInRequired_isNamedOnTheWire_andIsNotResolved() throws Exception {
        // The client branches on it to skip saving a session it was never given; the record
        // component's name is the contract, so it is asserted rather than assumed.
        String json = mapper.writeValueAsString(AuthenticationResult.requireSignIn());

        assertThat(json).contains("\"signInRequired\":true").contains("\"resolved\":false")
                .contains("\"userInfo\":null").contains("\"authToken\":null");
    }

    @Test
    void resolvedIsNamedOnTheWireByDeclaration_notByBeanAccident() throws Exception {
        // Reflection, because the wire shape survives the accident: isX already serializes as
        // "resolved", so a JSON assertion alone passes with the annotation deleted. The annotation
        // is the intent, and this is what fails when someone removes it.
        JsonProperty declared = AuthenticationResult.class.getMethod("isResolved")
                .getAnnotation(JsonProperty.class);

        assertThat(declared).isNotNull();
        assertThat(declared.value()).isEqualTo("resolved");
    }

    @Test
    void aPendingChoice_saysNotResolved() throws Exception {
        String json = mapper.writeValueAsString(AuthenticationResult.choicePending(7L,
                List.of(new MembershipOption(1L, 2L, "Acme", AccountStatus.ACTIVE, false, false, null)),
                false, "token"));

        assertThat(json).contains("\"resolved\":false").contains("\"authToken\":\"token\"");
    }
}
