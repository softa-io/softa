package io.softa.framework.orm.service.impl;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.entity.FileRecord;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.file.FileOpenKeyProperties;
import io.softa.framework.orm.file.FileOpenKeys;
import io.softa.framework.orm.file.FileOpenKeys.KeyStatus;
import io.softa.framework.orm.oss.OssClientService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a FileInfo's url is — a key for the account asking, or an unbound one when nobody is — and that
 * no storage link is signed until a key is opened.
 */
class FileServiceImplFileKeysTest {

    private static final String PREFIX = "/api/hcm";

    private final OssClientService oss = mock(OssClientService.class);
    private FileOpenKeys keys;
    private FileServiceImpl service;

    @BeforeEach
    void setUp() {
        FileOpenKeyProperties properties = new FileOpenKeyProperties();
        properties.setSecret("test-only-file-open-key-secret-0123456789");
        keys = new FileOpenKeys(properties, PREFIX);
        service = spy(new FileServiceImpl());
        ReflectionTestUtils.setField(service, "ossClientService", oss);
        ReflectionTestUtils.setField(service, "fileOpenKeys", keys);

        FileRecord record = new FileRecord();
        record.setId(5L);
        record.setFileName("Offer Letter.pdf");
        record.setFileType(FileType.PDF);
        record.setOssKey("1/EmpDocument/abc.pdf");
        record.setFileSize(12);
        doReturn(Optional.of(record)).when(service).getById(5L);
        doReturn(Optional.empty()).when(service).getById(6L);
    }

    private static <T> T as(Long userId, java.util.function.Supplier<T> action) {
        Context context = new Context();
        context.setUserId(userId);
        return ContextHolder.callWith(context, action::get);
    }

    @Test
    void aLoggedInUserGetsAKeyOnlyTheirAccountOpens_andNoStorageLinkIsSigned() {
        FileInfo info = as(42L, () -> service.getByFileId(5L)).orElseThrow();

        assertThat(info.getUrl()).startsWith(PREFIX + FileOpenKeys.USER_PATH + "5.");
        String key = info.getUrl().substring((PREFIX + FileOpenKeys.USER_PATH).length());
        assertThat(keys.checkUserKey(key, 42L).status()).isEqualTo(KeyStatus.VALID);
        assertThat(keys.checkUserKey(key, 43L).status()).isEqualTo(KeyStatus.WRONG_SIGNATURE);
        assertThat(info.getFileName()).isEqualTo("Offer Letter.pdf");
        verifyNoInteractions(oss);
    }

    @Test
    void withNobodyLoggedIn_theKeyIsUnbound() {
        // A public page, a scheduled job, a flow nobody triggered: no userId in the context.
        FileInfo info = as(null, () -> service.getByFileId(5L)).orElseThrow();

        assertThat(info.getUrl()).startsWith(PREFIX + FileOpenKeys.UNBOUND_PATH + "5.");
        String key = info.getUrl().substring((PREFIX + FileOpenKeys.UNBOUND_PATH).length());
        assertThat(keys.checkUnboundKey(key).status()).isEqualTo(KeyStatus.VALID);
        verifyNoInteractions(oss);
    }

    @Test
    void theStorageLinkIsSignedWhenAKeyIsOpened_forTheConfiguredLifetime() {
        when(oss.getDownloadUrl("1/EmpDocument/abc.pdf", "Offer Letter.pdf")).thenReturn("https://storage/dl");
        when(oss.getPreSignedUrl("1/EmpDocument/abc.pdf", "Offer Letter.pdf")).thenReturn("https://storage/inline");

        assertThat(service.getStorageLink(5L, true)).contains("https://storage/dl");
        assertThat(service.getStorageLink(5L, false)).contains("https://storage/inline");
        // The two-argument overloads read oss.url-expire-seconds; the explicit-lifetime ones are no
        // longer called from here, so the setting finally applies.
        verify(oss, never()).getDownloadUrl(anyString(), anyInt(), anyString());
        verify(oss, never()).getPreSignedUrl(anyString(), anyInt(), anyString());
    }

    @Test
    void noFile_noLink() {
        assertThat(service.getStorageLink(6L, false)).isEmpty();
        assertThat(service.getStorageLink(null, false)).isEmpty();
        verifyNoInteractions(oss);
    }
}
