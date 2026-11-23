package io.softa.starter.file.service.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.entity.ExportHistory;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.entity.ImportHistory;
import io.softa.starter.file.excel.export.ExcelSheetData;
import io.softa.starter.file.excel.export.strategy.ExportByDynamic;
import io.softa.starter.file.excel.export.strategy.ExportByFileTemplate;
import io.softa.starter.file.excel.export.support.ExcelUploadService;
import io.softa.starter.file.excel.export.support.ExportDataFetcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The files an import and an export GENERATE — not the one a user uploads — are stamped with their
 * holder too.
 *
 * <p>{@code ImportUploadStampsTheHolderTest} captures {@code fileService.uploadFile}, which is only
 * four of the ten call sites the fix touched. The other six go through
 * {@code excelUploadService.generateFileAndUpload} — a different collaborator, so that test never
 * sees them, and until this class they rested on an assertion about the source text. Anyone who
 * reworded the call kept the test green and the product broken.
 *
 * <p>These are the files a user actually clicks: the import-result workbook in My Import History and
 * every export. Both hang on a history row, so both need the history's stamp.
 */
class GeneratedFilesAreStampedWithTheirHolderTest {

    private static ExcelUploadService uploadServiceReturning() {
        ExcelUploadService uploadService = mock(ExcelUploadService.class);
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileId(11L);
        when(uploadService.generateFileAndUpload(anyString(), anyString(), any(ExcelSheetData.class)))
                .thenReturn(fileInfo);
        return uploadService;
    }

    private static String stampOf(ExcelUploadService uploadService) {
        ArgumentCaptor<String> model = ArgumentCaptor.forClass(String.class);
        verify(uploadService).generateFileAndUpload(model.capture(), anyString(), any(ExcelSheetData.class));
        return model.getValue();
    }

    private static ImportTemplateDTO templateDto() {
        ImportTemplateDTO dto = new ImportTemplateDTO();
        dto.setModelName("Employee");
        dto.setFileName("employees");
        ImportFieldDTO field = new ImportFieldDTO();
        field.setFieldName("code");
        field.setHeader("Employee Code");
        dto.addImportField(field);
        return dto;
    }

    @Test
    @DisplayName("the import-result workbook is stamped ImportHistory, not the imported model")
    void theImportResultWorkbook() {
        ImportServiceImpl service = new ImportServiceImpl();
        ExcelUploadService uploadService = uploadServiceReturning();
        ReflectionTestUtils.setField(service, "excelUploadService", uploadService);
        ImportDataDTO data = new ImportDataDTO();
        data.setFailedRows(List.of());

        ReflectionTestUtils.invokeMethod(service, "generateResultExcel", templateDto(), data, "Import Result");

        assertThat(stampOf(uploadService))
                .as("this file lands on ImportHistory.failedFileId — the one a user clicks in My Import History")
                .isEqualTo(ImportHistory.class.getSimpleName())
                .isNotEqualTo("Employee");
    }

    @Test
    @DisplayName("the validation-result workbook is stamped ImportHistory too")
    void theValidationResultWorkbook() {
        ImportServiceImpl service = new ImportServiceImpl();
        ExcelUploadService uploadService = uploadServiceReturning();
        ReflectionTestUtils.setField(service, "excelUploadService", uploadService);
        ImportDataDTO data = new ImportDataDTO();
        data.setRows(List.of());
        data.setFailedRows(List.of());

        ReflectionTestUtils.invokeMethod(service, "generateResultExcel", templateDto(), data, "Validation Result");

        assertThat(stampOf(uploadService)).isEqualTo(ImportHistory.class.getSimpleName());
    }

    @Test
    @DisplayName("a single-model export is stamped ExportHistory, not the model exported")
    void aSingleModelExport() {
        ExportByDynamic strategy = new ExportByDynamic();
        ExcelUploadService uploadService = uploadServiceReturning();
        ExportDataFetcher fetcher = mock(ExportDataFetcher.class);
        when(fetcher.fetchRows(anyString(), any(), any())).thenReturn(List.of());
        ReflectionTestUtils.setField(strategy, "excelUploadService", uploadService);
        ReflectionTestUtils.setField(strategy, "exportDataFetcher", fetcher);

        // setLabel is package-private on MetaModel, so mock rather than build one — the strategy
        // only reads the label to name the sheet.
        MetaModel model = mock(MetaModel.class);
        when(model.getLabel()).thenReturn("Employee");
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            modelManager.when(() -> ModelManager.getModel("Employee")).thenReturn(model);
            modelManager.when(() -> ModelManager.getLastFieldOfCascaded(anyString(), anyString()))
                    .thenReturn(new MetaField());

            strategy.export("Employee", new FlexQuery(List.of("code")));
        }

        assertThat(stampOf(uploadService))
                .as("the workbook hangs on ExportHistory.fileId, so that is what may claim it")
                .isEqualTo(ExportHistory.class.getSimpleName())
                .isNotEqualTo("Employee");
    }

    @Test
    @DisplayName("a file-template export is stamped ExportHistory, not the model exported")
    void aFileTemplateExport() throws Exception {
        byte[] template;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Sheet1").createRow(0).createCell(0).setCellValue("{{ code }}");
            workbook.write(out);
            template = out.toByteArray();
        }
        ExportByFileTemplate strategy = new ExportByFileTemplate();
        FileService fileService = mock(FileService.class);
        when(fileService.downloadStream(7L)).thenAnswer(invocation -> new ByteArrayInputStream(template));
        ExportDataFetcher fetcher = mock(ExportDataFetcher.class);
        when(fetcher.fetchRows(anyString(), any(), any())).thenReturn(List.of(Map.of("code", "E1")));
        ExcelUploadService uploadService = mock(ExcelUploadService.class);
        when(uploadService.uploadExcelBytes(anyString(), anyString(), any(byte[].class))).thenReturn(new FileInfo());
        ReflectionTestUtils.setField(strategy, "fileService", fileService);
        ReflectionTestUtils.setField(strategy, "exportDataFetcher", fetcher);
        ReflectionTestUtils.setField(strategy, "excelUploadService", uploadService);
        ExportTemplate exportTemplate = new ExportTemplate();
        exportTemplate.setModelName("Employee");
        exportTemplate.setFileName("employees");
        exportTemplate.setFileId(7L);

        strategy.export(exportTemplate, new FlexQuery());

        ArgumentCaptor<String> model = ArgumentCaptor.forClass(String.class);
        verify(uploadService).uploadExcelBytes(model.capture(), anyString(), any(byte[].class));
        assertThat(model.getValue())
                .as("the workbook hangs on ExportHistory.fileId, so that is what may claim it")
                .isEqualTo(ExportHistory.class.getSimpleName())
                .isNotEqualTo("Employee");
    }
}
