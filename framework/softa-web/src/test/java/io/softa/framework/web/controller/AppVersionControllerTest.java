package io.softa.framework.web.controller;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.web.config.AppVersionProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppVersionControllerTest {

    private final AppVersionProperties versions = new AppVersionProperties();
    private final AppVersionController controller = new AppVersionController(versions);

    @Test
    void aPlatformsVersions_asConfigured() {
        AppVersionProperties.Release android = new AppVersionProperties.Release();
        android.setMinVersion("1.0.0");
        android.setLatestVersion("1.3.0");
        android.setStoreUrl("https://play.google.com/store/apps/details?id=example");
        versions.getVersions().put("android", android);

        AppVersionController.VersionView view = controller.version("Android").getData();

        assertThat(view.minVersion()).isEqualTo("1.0.0");
        assertThat(view.latestVersion()).isEqualTo("1.3.0");
        assertThat(view.storeUrl()).startsWith("https://play.google.com");
        assertThat(view.releaseNotes()).isNull();
    }

    @Test
    void anUnknownPlatform_isRefused() {
        assertThatThrownBy(() -> controller.version("symbian")).isInstanceOf(BusinessException.class);
    }
}
