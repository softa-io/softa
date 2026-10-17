package io.softa.starter.file.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.entity.ImportTemplateField;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.service.ImportTemplateService;
import io.softa.starter.file.vo.ImportWizard;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The import endpoints are shared by every model, so the endpoint gate cannot tell which model a call
 * imports. Each entry point asks for the import of the model it is about to write — the template's, or
 * the wizard's — and a refusal comes before the upload, leaving nothing behind.
 */
class ImportChecksTheModelGrantTest {

    private static final Long TEMPLATE_ID = 7L;

    private ImportServiceImpl service;
    private FileService fileService;
    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        service = new ImportServiceImpl();
        fileService = mock(FileService.class);
        permissionService = mock(PermissionService.class);
        // Holds the import of some other model, not of Employee.
        when(permissionService.hasModelImportGrant(anyString())).thenReturn(true);
        when(permissionService.hasModelImportGrant("Employee")).thenReturn(false);

        ImportTemplate template = new ImportTemplate();
        template.setId(TEMPLATE_ID);
        template.setModelName("Employee");
        template.setImportRule(ImportRule.CREATE_OR_UPDATE);
        template.setImportFields(List.of(new ImportTemplateField()));
        ImportTemplateService templateService = mock(ImportTemplateService.class);
        when(templateService.getById(any(), any(SubQueries.class))).thenReturn(Optional.of(template));

        ReflectionTestUtils.setField(service, "fileService", fileService);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "importTemplateService", templateService);
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "employees.xlsx", null, new byte[]{1});
    }

    private static ImportWizard wizard() {
        ImportWizard wizard = new ImportWizard();
        wizard.setModelName("Employee");
        wizard.setFileName("employees.xlsx");
        wizard.setImportRule(ImportRule.CREATE_OR_UPDATE);
        wizard.setFile(file());
        return wizard;
    }

    private void refusedBeforeTheUpload(Runnable entryPoint) {
        assertThatThrownBy(entryPoint::run).isInstanceOf(PermissionException.class);
        verify(permissionService).hasModelImportGrant("Employee");
        verify(fileService, never()).uploadFile(anyString(), any());
    }

    @Test
    @DisplayName("importByTemplate asks for the template's model")
    void importByTemplate() {
        refusedBeforeTheUpload(() -> service.importByTemplate(TEMPLATE_ID, file(), Map.of()));
    }

    @Test
    @DisplayName("validateByTemplate asks for the template's model")
    void validateByTemplate() {
        refusedBeforeTheUpload(() -> service.validateByTemplate(TEMPLATE_ID, file(), Map.of()));
    }

    @Test
    @DisplayName("importByDynamic asks for the wizard's model")
    void importByDynamic() {
        refusedBeforeTheUpload(() -> service.importByDynamic(wizard()));
    }

    @Test
    @DisplayName("validateByDynamic asks for the wizard's model")
    void validateByDynamic() {
        refusedBeforeTheUpload(() -> service.validateByDynamic(wizard()));
    }

    @Test
    @DisplayName("getTemplateFile asks for the template's model")
    void getTemplateFile() {
        assertThatThrownBy(() -> service.getTemplateFile(TEMPLATE_ID)).isInstanceOf(PermissionException.class);
        verify(permissionService).hasModelImportGrant("Employee");
    }
}
