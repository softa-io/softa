package io.softa.starter.file.service.impl;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.file.entity.ImportHistory;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.service.ImportHistoryService;
import io.softa.starter.file.service.ImportTemplateFieldService;
import io.softa.starter.file.service.ImportTemplateService;
import io.softa.starter.file.vo.ImportWizard;
import io.softa.framework.orm.service.FileService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What model an import's upload is stamped with, asserted on the call rather than on the source.
 *
 * <p>{@code FileRecord.modelName} decides who may later claim the record, and the row that ends up
 * holding an import's spreadsheet is the ImportHistory one — never a row of the model being
 * imported. Stamped with the imported model, writing {@code ImportHistory.originalFileId} is refused
 * with "File … is not yours to attach" and the whole import fails, which is what happened in 2.6.0.
 *
 * <p>All four entry points are covered because they are four separate call sites of one rule, and
 * the one that broke in production was not the one anybody would have written a test for first.
 */
class ImportUploadStampsTheHolderTest {

    private static final Long FILE_ID = 99L;
    private static final Long TEMPLATE_ID = 7L;

    private ImportServiceImpl service;
    private FileService fileService;

    @BeforeEach
    void setUp() {
        service = new ImportServiceImpl();
        fileService = mock(FileService.class);
        when(fileService.uploadFile(anyString(), any())).thenReturn(FILE_ID);

        ImportTemplateService templateService = mock(ImportTemplateService.class);
        when(templateService.getById(any(), any(io.softa.framework.orm.domain.SubQueries.class)))
                .thenReturn(java.util.Optional.of(template()));

        ImportTemplateFieldService fieldService = mock(ImportTemplateFieldService.class);
        when(fieldService.searchList(any(io.softa.framework.orm.domain.FlexQuery.class))).thenReturn(List.of());

        ImportHistoryService historyService = mock(ImportHistoryService.class);
        when(historyService.createOne(any(ImportHistory.class))).thenReturn(1L);

        ReflectionTestUtils.setField(service, "fileService", fileService);
        ReflectionTestUtils.setField(service, "importTemplateService", templateService);
        ReflectionTestUtils.setField(service, "importTemplateFieldService", fieldService);
        ReflectionTestUtils.setField(service, "importHistoryService", historyService);
        io.softa.framework.orm.service.PermissionService permissionService =
                mock(io.softa.framework.orm.service.PermissionService.class);
        when(permissionService.hasModelImportGrant(anyString())).thenReturn(true);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "asyncImportProducer",
                mock(io.softa.starter.file.message.AsyncImportProducer.class));
    }

    /** Async so the call returns at the queue rather than running a pipeline — the assertion is
     *  about the upload, which happens before either path is chosen. */
    private ImportTemplate template() {
        ImportTemplate template = new ImportTemplate();
        template.setId(TEMPLATE_ID);
        template.setName("Add or Update Employees (SG)");
        template.setModelName("Employee");
        template.setImportRule(ImportRule.CREATE_OR_UPDATE);
        template.setImportFields(List.of(new io.softa.starter.file.entity.ImportTemplateField()));
        template.setSyncImport(false);
        return template;
    }

    private MockMultipartFile file() {
        return new MockMultipartFile("file", "employees.xlsx", null, new byte[]{1});
    }

    /**
     * Run an entry point far enough to have uploaded, and swallow whatever the un-stubbed pipeline
     * does afterwards.
     *
     * <p>The upload is the first thing each of these methods does and the only thing under test.
     * Stubbing the rest — parser, row pipeline, queue — would make this a test of the pipeline, and
     * it would go red every time the pipeline changed, which is not what it is here to notice.
     */
    private void uploadThenStop(Runnable entryPoint) {
        try {
            entryPoint.run();
        } catch (RuntimeException ignored) {
            // Past the upload; the capture below is what matters.
        }
    }

    private String stampedModel() {
        ArgumentCaptor<String> model = ArgumentCaptor.forClass(String.class);
        verify(fileService).uploadFile(model.capture(), any());
        return model.getValue();
    }

    @Test
    @DisplayName("importByTemplate stamps ImportHistory, not the imported model")
    void importByTemplate() {
        uploadThenStop(() -> service.importByTemplate(TEMPLATE_ID, file(), Map.of()));

        assertThat(stampedModel())
                .as("the file hangs on ImportHistory, so only ImportHistory may claim it")
                .isEqualTo(ImportHistory.class.getSimpleName())
                .isNotEqualTo("Employee");
    }

    @Test
    @DisplayName("validateByTemplate stamps ImportHistory too — it writes a history row as well")
    void validateByTemplate() {
        uploadThenStop(() -> service.validateByTemplate(TEMPLATE_ID, file(), Map.of()));

        assertThat(stampedModel()).isEqualTo(ImportHistory.class.getSimpleName());
    }

    @Test
    @DisplayName("importByDynamic stamps ImportHistory, not the wizard's model")
    void importByDynamic() {
        uploadThenStop(() -> service.importByDynamic(wizard()));

        assertThat(stampedModel()).isEqualTo(ImportHistory.class.getSimpleName());
    }

    @Test
    @DisplayName("validateByDynamic stamps ImportHistory, not the wizard's model")
    void validateByDynamic() {
        uploadThenStop(() -> service.validateByDynamic(wizard()));

        assertThat(stampedModel()).isEqualTo(ImportHistory.class.getSimpleName());
    }

    private ImportWizard wizard() {
        ImportWizard wizard = new ImportWizard();
        wizard.setModelName("Employee");
        wizard.setFileName("employees.xlsx");
        wizard.setImportRule(ImportRule.CREATE_OR_UPDATE);
        wizard.setFile(file());
        return wizard;
    }
}
