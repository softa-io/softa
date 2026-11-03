package io.softa.starter.user.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** The client address is the one our own proxy recorded, not whatever the caller claims. */
class ClientInfoTest {

    @Test
    void theLastForwardedHop_isTheAddress_earlierOnesAreTheCallersOwnWords() {
        // A caller sends "1.2.3.4"; the proxy in front of us appends the address it saw.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9");

        assertThat(ClientInfo.of(request).ip()).isEqualTo("203.0.113.9");
    }

    @Test
    void aSingleHop_orNone_fallsBackAsBefore() {
        MockHttpServletRequest single = new MockHttpServletRequest();
        single.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(ClientInfo.of(single).ip()).isEqualTo("203.0.113.9");

        MockHttpServletRequest direct = new MockHttpServletRequest();
        direct.setRemoteAddr("198.51.100.7");
        assertThat(ClientInfo.of(direct).ip()).isEqualTo("198.51.100.7");
    }
}
