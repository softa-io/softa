package io.softa.framework.web.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.constant.BaseConstant;
import io.softa.framework.base.exception.SystemException;
import io.softa.framework.base.security.EncryptUtils;
import io.softa.framework.base.security.algorithm.AESEncryption;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;

class ToolkitServiceFixUnencryptedDataTest {

    private static final String MODEL = "EmpInfo";
    private static final String FIELD = "nric";
    private static final String PASSWORD = "right-password";

    /** Produced by encrypt("S1234567D", "right-password"). */
    private static final String CIPHERTEXT = "pTOZ/yOb8+LBdxFTz02oD7nyD9VMnXxbWRLEkW3oRJg=";

    private final ToolkitServiceImpl toolkitService = new ToolkitServiceImpl();

    @SuppressWarnings("rawtypes")
    private ModelService modelService;
    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(EncryptUtils.class, "encryptor", new AESEncryption());
        ReflectionTestUtils.setField(EncryptUtils.class, "password", PASSWORD);

        modelService = Mockito.mock(ModelService.class);
        ReflectionTestUtils.setField(toolkitService, "modelService", modelService);

        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", FIELD);
        ReflectionTestUtils.setField(metaField, "encrypted", true);

        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.getModelField(MODEL, FIELD)).thenReturn(metaField);
        modelManager.when(() -> ModelManager.isTimelineModel(MODEL)).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    private static Map<String, Object> row(Object id, String nric) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("nric", nric);
        return row;
    }

    /**
     * A short page ends the cursor loop, so the whole scan is this one page.
     */
    private void givenSinglePage(List<Map<String, Object>> rows) {
        when(modelService.searchPage(anyString(), any(), any()))
                .thenAnswer(invocation -> Page.<Map<String, Object>>ofCursorPage(rows.size() + 1).setRows(rows));
    }

    private static List<Map<String, Object>> mixedRows() {
        return new ArrayList<>(List.of(
                row(11L, "1234567890123456"),         // plaintext, decodes to 12 bytes
                row(12L, CIPHERTEXT),                 // already encrypted
                row(13L, "ABCDEFGHIJKLMNOPQRSTUVWX"), // plaintext, decodes to 18 bytes
                row(14L, "hello@example.com")));      // plaintext, not Base64
    }

    @Test
    @SuppressWarnings("unchecked")
    void fixUnencryptedData_encryptsOnlyThePlaintextRows() {
        givenSinglePage(mixedRows());

        assertThat(toolkitService.fixUnencryptedData(MODEL, FIELD, false)).isEqualTo(3L);

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(modelService).updateList(eq(MODEL), captor.capture());
        assertThat(captor.getValue()).extracting(r -> r.get("id")).containsExactly(11L, 13L, 14L);
    }

    /**
     * The dry run has to report exactly what the real run would change, and write nothing.
     */
    @Test
    void fixUnencryptedData_reportsTheSameCountAndWritesNothing_whenDryRun() {
        givenSinglePage(mixedRows());

        assertThat(toolkitService.fixUnencryptedData(MODEL, FIELD, true)).isEqualTo(3L);

        verify(modelService, never()).updateList(anyString(), any());
    }

    /**
     * A row whose encrypted field is null or empty holds nothing to encrypt: it must be neither
     * counted nor rewritten, or a mostly-empty column reports every one of its rows as fixed.
     */
    @Test
    void fixUnencryptedData_ignoresRowsWithNoValue() {
        givenSinglePage(new ArrayList<>(List.of(
                row(11L, null), row(12L, ""), row(13L, CIPHERTEXT), row(14L, "hello@example.com"))));

        assertThat(toolkitService.fixUnencryptedData(MODEL, FIELD, true)).isEqualTo(1L);
    }

    /**
     * Encrypting under a key the existing rows were not written with would leave the column half
     * readable, so a mismatch stops the scan before anything is written - in a dry run too.
     */
    @Test
    void fixUnencryptedData_failsBeforeWriting_whenTheKeyDoesNotMatch() {
        ReflectionTestUtils.setField(EncryptUtils.class, "password", "wrong-password");
        givenSinglePage(mixedRows());

        assertThatThrownBy(() -> toolkitService.fixUnencryptedData(MODEL, FIELD, false))
                .isInstanceOf(SystemException.class)
                .hasMessageContaining("security.encryption.password");

        verify(modelService, never()).updateList(anyString(), any());
    }

    @Test
    void fixUnencryptedData_rejectsAFieldThatIsNotEncrypted() {
        MetaField plainField = new MetaField();
        ReflectionTestUtils.setField(plainField, "encrypted", false);
        modelManager.when(() -> ModelManager.getModelField(MODEL, FIELD)).thenReturn(plainField);

        assertThatThrownBy(() -> toolkitService.fixUnencryptedData(MODEL, FIELD, true))
                .hasMessageContaining("not an encrypted field");
    }

    /**
     * A full page is followed by the next one, and the pass ends at the first page that is not full. It
     * used to read the first page forever, so a model with 1,000 rows or more never finished.
     */
    @Test
    void fixUnencryptedData_readsThePageAfterAFullOne() {
        int pageSize = BaseConstant.DEFAULT_BATCH_SIZE;
        givenPages(plaintextRows(0, pageSize), plaintextRows(pageSize, 1));

        assertThat(toolkitService.fixUnencryptedData(MODEL, FIELD, true)).isEqualTo(pageSize + 1L);
    }

    /** A last page that happens to be full is followed by one empty page, and the pass stops there. */
    @Test
    void fixUnencryptedData_stopsAtTheEmptyPageAfterAFullLastPage() {
        int pageSize = BaseConstant.DEFAULT_BATCH_SIZE;
        givenPages(plaintextRows(0, pageSize), plaintextRows(pageSize, pageSize), List.of());

        assertThat(toolkitService.fixUnencryptedData(MODEL, FIELD, true)).isEqualTo(2L * pageSize);
    }

    /**
     * Serves the pages by the number asked for, and fails the test instead of looping when asked for more
     * reads than there are pages.
     */
    @SafeVarargs
    private void givenPages(List<Map<String, Object>>... pages) {
        AtomicInteger reads = new AtomicInteger();
        when(modelService.searchPage(anyString(), any(), any())).thenAnswer(invocation -> {
            if (reads.incrementAndGet() > pages.length) {
                throw new AssertionError("Read " + reads + " pages, but there are only " + pages.length);
            }
            Page<Map<String, Object>> page = invocation.getArgument(2);
            return page.setRows(pages[page.getPageNumber() - 1]);
        });
    }

    private static List<Map<String, Object>> plaintextRows(int from, int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (long id = from + 1; id <= from + count; id++) {
            rows.add(row(id, "plain-" + id));
        }
        return rows;
    }
}
