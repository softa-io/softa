package io.softa.framework.web.filter;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.softa.framework.web.config.AppVersionProperties;
import io.softa.framework.web.enums.IdentifyType;
import io.softa.framework.web.filter.context.IdentityResolver;
import io.softa.framework.web.session.SessionStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An APP below the minimum version is stopped at every signed-in endpoint; the version check, sign-in,
 * the ways out and Web are never stopped here.
 */
class AppVersionGateTest {

    private static final String UPGRADE = "\"errorCode\":\"UPGRADE_REQUIRED\"";

    private final AppVersionProperties versions = new AppVersionProperties();
    private final IdentityResolver identity = mock(IdentityResolver.class);

    AppVersionGateTest() {
        AppVersionProperties.Release ios = new AppVersionProperties.Release();
        ios.setMinVersion("1.2.0");
        ios.setLatestVersion("1.4.0");
        versions.getVersions().put("ios", ios);
        when(identity.getIdentifyRequired(anyString())).thenReturn(IdentifyType.USER);
        when(identity.getIdentifyRequired("/app/version")).thenReturn(IdentifyType.ANONYMOUS);
    }

    private MockHttpServletResponse call(String method, String path, String platform, String version) throws Exception {
        return send(new MockHttpServletRequest(method, path), platform, version);
    }

    private MockHttpServletResponse send(MockHttpServletRequest request, String platform, String version) throws Exception {
        if (platform != null) {
            request.addHeader(SessionStore.APP_PLATFORM_HEADER, platform);
        }
        if (version != null) {
            request.addHeader(SessionStore.APP_VERSION_HEADER, version);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AppVersionGate(versions, identity).doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void anOldApp_isToldToUpgrade() throws Exception {
        assertThat(call("GET", "/orders/1", "iOS", "1.1.9").getContentAsString()).contains(UPGRADE);
    }

    @Test
    void aSupportedApp_passes() throws Exception {
        assertThat(call("GET", "/orders/1", "ios", "1.2.0").getContentAsString()).isEmpty();
        assertThat(call("GET", "/orders/1", "ios", "1.10.0").getContentAsString()).isEmpty();
    }

    @Test
    void theVersionCheckItself_andWeb_areNeverStopped() throws Exception {
        assertThat(call("GET", "/app/version", "ios", "0.9.0").getContentAsString()).isEmpty();
        assertThat(call("GET", "/orders/1", null, null).getContentAsString()).isEmpty();
    }

    @Test
    void nothingConfigured_blocksNothing() throws Exception {
        // A platform with no floor; and what every application without an APP looks like.
        assertThat(call("GET", "/orders/1", "android", "0.1.0").getContentAsString()).isEmpty();
        versions.getVersions().clear();
        assertThat(call("GET", "/orders/1", "ios", "0.1.0").getContentAsString()).isEmpty();
    }

    @Test
    void oneHeaderAlone_hasNothingToCompare_andPasses() throws Exception {
        assertThat(call("GET", "/orders/1", "ios", null).getContentAsString()).isEmpty();
        assertThat(call("GET", "/orders/1", null, "0.1.0").getContentAsString()).isEmpty();
    }

    @Test
    void versionsCompareByNumber_notByText() {
        assertThat(AppVersionProperties.isOlder("1.9.3", "1.10.0")).isTrue();
        assertThat(AppVersionProperties.isOlder("1.10.0", "1.9.3")).isFalse();
        assertThat(AppVersionProperties.isOlder("1.2", "1.2.0")).isFalse();
        assertThat(AppVersionProperties.isOlder("1.2.0-beta", "1.2.1")).isTrue();
        assertThat(AppVersionProperties.isOlder("", "1.0.0")).isFalse();
        // A leading "v" is not an empty first part read as 0.
        assertThat(AppVersionProperties.isOlder("v2.0.0", "1.0.0")).isFalse();
        assertThat(AppVersionProperties.isOlder("V1.1.9", "1.2.0")).isTrue();
        assertThat(AppVersionProperties.isOlder("1.2.0", "v1.2.0")).isFalse();
    }

    // ── the ways out ────────────────────────────────────────────────

    @Test
    void signingOut_isOpenWithNothingConfigured() throws Exception {
        assertThat(call("POST", "/UserAccount/logout", "ios", "1.0.0").getContentAsString()).isEmpty();
    }

    @Test
    void aConfiguredPath_isLimitedToItsMethod() throws Exception {
        versions.setUpgradeExemptPaths(List.of("DELETE /devices/*"));

        assertThat(call("DELETE", "/devices/token-1", "ios", "1.0.0").getContentAsString()).isEmpty();
        assertThat(call("POST", "/devices/token-1", "ios", "1.0.0").getContentAsString()).contains(UPGRADE);
        // The collection is not one of its items.
        assertThat(call("DELETE", "/devices", "ios", "1.0.0").getContentAsString()).contains(UPGRADE);
    }

    @Test
    void aConfiguredPath_withoutAMethod_coversThemAll_andSigningOutStaysOpen() throws Exception {
        versions.setUpgradeExemptPaths(List.of("/help/**"));

        assertThat(call("GET", "/help/faq", "ios", "1.0.0").getContentAsString()).isEmpty();
        assertThat(call("POST", "/help/faq", "ios", "1.0.0").getContentAsString()).isEmpty();
        assertThat(call("POST", "/UserAccount/logout", "ios", "1.0.0").getContentAsString()).isEmpty();
    }

    @Test
    void exemptPaths_doNotIncludeTheContextPath() throws Exception {
        versions.setUpgradeExemptPaths(List.of("DELETE /devices/*"));
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/devices/token-1");
        request.setContextPath("/api");

        assertThat(send(request, "ios", "1.0.0").getContentAsString()).isEmpty();
    }

    @Test
    void aMalformedEntry_stopsTheBoot() {
        versions.setUpgradeExemptPaths(List.of("DELETE /devices/* extra"));

        assertThatThrownBy(() -> new AppVersionGate(versions, identity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.upgrade-exempt-paths");
    }
}
