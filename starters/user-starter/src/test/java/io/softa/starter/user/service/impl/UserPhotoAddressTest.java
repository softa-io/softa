package io.softa.starter.user.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.UserInfo;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.file.FileOpenKeyProperties;
import io.softa.framework.orm.file.FileOpenKeys;
import io.softa.framework.orm.file.FileOpenKeys.KeyStatus;
import io.softa.starter.user.dto.UserProfileDTO;
import io.softa.starter.user.service.UserProfileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The photo address on UserInfo, and the photo a social sign-up brings along. A photo once made every
 * login of its owner fail (a storage link signed for longer than storage allows, built into the cached
 * UserInfo) and every first social sign-up with one fail too (the null that failure left behind).
 */
class UserPhotoAddressTest {

    private static FileOpenKeys keys() {
        FileOpenKeyProperties properties = new FileOpenKeyProperties();
        properties.setOpenKeySecret("test-only-file-open-key-secret-0123456789");
        return new FileOpenKeys(properties, "/api/hcm");
    }

    @Test
    void thePhotoAddressIsAKeyForTheAccountTheUserInfoBelongsTo() {
        FileOpenKeys keys = keys();
        UserProfileServiceImpl service = new UserProfileServiceImpl();
        ReflectionTestUtils.setField(service, "fileOpenKeys", keys);
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(42L);
        userInfo.setPhotoId(9L);

        String url = service.withPhotoUrl(userInfo).getPhotoUrl();

        assertThat(url).startsWith("/api/hcm/file/open/9.");
        String key = url.substring("/api/hcm/file/open/".length());
        assertThat(keys.checkUserKey(key, 42L).status()).isEqualTo(KeyStatus.VALID);
        assertThat(keys.checkUserKey(key, 43L).status()).isEqualTo(KeyStatus.WRONG_SIGNATURE);
    }

    @Test
    void noPhoto_noAddress() {
        UserProfileServiceImpl service = new UserProfileServiceImpl();
        ReflectionTestUtils.setField(service, "fileOpenKeys", keys());
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(42L);
        userInfo.setPhotoUrl("/stale");

        assertThat(service.withPhotoUrl(userInfo).getPhotoUrl()).isNull();
        assertThat(service.withPhotoUrl(null)).isNull();
    }

    @Test
    void aSocialPhotoThatCannotBeStored_leavesTheNewProfileWithoutOne_insteadOfFailingTheSignUp() {
        UserProfileService profiles = mock(UserProfileService.class);
        when(profiles.fetchPhotoFromURL("https://photos.example/ada.jpg", null)).thenReturn(null);
        OAuth2ServiceImpl oauth = new OAuth2ServiceImpl();
        ReflectionTestUtils.setField(oauth, "profileService", profiles);

        UserProfileDTO dto = oauth.buildProfileFromSocial("Ada Lovelace", "https://photos.example/ada.jpg");

        assertThat(dto.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(dto.getPhotoId()).isNull();
    }

    @Test
    void aStoredSocialPhotoIsKeptByItsId() {
        UserProfileService profiles = mock(UserProfileService.class);
        FileInfo stored = new FileInfo();
        stored.setFileId(9L);
        when(profiles.fetchPhotoFromURL("https://photos.example/ada.jpg", null)).thenReturn(stored);
        OAuth2ServiceImpl oauth = new OAuth2ServiceImpl();
        ReflectionTestUtils.setField(oauth, "profileService", profiles);

        assertThat(oauth.buildProfileFromSocial("Ada Lovelace", "https://photos.example/ada.jpg").getPhotoId())
                .isEqualTo(9L);
    }
}
