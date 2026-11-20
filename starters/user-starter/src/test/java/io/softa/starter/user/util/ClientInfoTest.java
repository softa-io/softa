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

    @Test
    void behindTwoAppendingProxies_theAddressIsTheSecondFromTheRight() {
        // A load balancer and a reverse proxy both append: the caller's own entry stays ignored.
        ClientInfo.trustProxies(2);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9, 10.0.0.5");
            assertThat(ClientInfo.of(request).ip()).isEqualTo("203.0.113.9");

            // Fewer entries than proxies: the leftmost is all there is.
            MockHttpServletRequest short_ = new MockHttpServletRequest();
            short_.addHeader("X-Forwarded-For", "203.0.113.9");
            assertThat(ClientInfo.of(short_).ip()).isEqualTo("203.0.113.9");
        } finally {
            ClientInfo.trustProxies(1);
        }
    }

    @Test
    void zeroOrLessTrustedProxies_isReadAsOne() {
        ClientInfo.trustProxies(0);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9");
            assertThat(ClientInfo.of(request).ip()).isEqualTo("203.0.113.9");
        } finally {
            ClientInfo.trustProxies(1);
        }
    }

    @Test
    void xRealIp_isTheCallersOwnWords_unlessConfiguredAsTrusted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("X-Real-IP", "1.2.3.4");
        assertThat(ClientInfo.of(request).ip()).isEqualTo("10.0.0.5");

        ClientInfo.trustRealIp(true);
        try {
            assertThat(ClientInfo.of(request).ip()).isEqualTo("1.2.3.4");
        } finally {
            ClientInfo.trustRealIp(false);
        }
    }

    @Test
    void theAppsDeviceHeaders_areRead_andWebSendsNone() {
        MockHttpServletRequest app = new MockHttpServletRequest();
        app.addHeader(ClientInfo.PLATFORM_HEADER, "iOS");
        app.addHeader(ClientInfo.APP_VERSION_HEADER, "1.2.0");
        app.addHeader(ClientInfo.DEVICE_MODEL_HEADER, " iPhone12,3 ");
        app.addHeader(ClientInfo.OS_VERSION_HEADER, "18.1");
        ClientInfo client = ClientInfo.of(app);
        assertThat(client.platform()).isEqualTo("ios");
        assertThat(client.deviceModel()).isEqualTo("iPhone12,3");
        assertThat(client.osVersion()).isEqualTo("18.1");

        ClientInfo web = ClientInfo.of(new MockHttpServletRequest());
        assertThat(web.deviceModel()).isNull();
        assertThat(web.osVersion()).isNull();
    }

    @Test
    void anOverlongDeviceHeader_isCutToItsColumn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientInfo.DEVICE_MODEL_HEADER, "m".repeat(200));
        request.addHeader(ClientInfo.OS_VERSION_HEADER, "9".repeat(100));
        assertThat(ClientInfo.of(request).deviceModel()).hasSize(64);
        assertThat(ClientInfo.of(request).osVersion()).hasSize(32);
    }
}
