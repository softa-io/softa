package io.softa.starter.file.excel.export.strategy;

import java.util.List;
import java.util.Map;

import org.apache.fesod.sheet.write.handler.WriteHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.excel.export.ExcelSheetData;
import io.softa.starter.file.excel.export.ResolvedTemplateSheet;
import io.softa.starter.file.excel.export.support.ExcelUploadService;
import io.softa.starter.file.excel.export.support.ExportDataFetcher;
import io.softa.starter.file.excel.export.support.ExportTemplateResolver;
import io.softa.starter.file.excel.style.CustomExportStyleHandler;
import io.softa.starter.file.excel.style.DecimalColumnFormatHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Both export paths hand the sheet the decimal columns' number formats: the dynamic export from the
 * fields that were picked, the template export from the template's exported fields. A sheet with no
 * decimal column gets no such handler, which the sheet builder skips as a null.
 */
class ExportColumnHandlersTest {

    private static final String MODEL = "Order";

    private static MetaField field(FieldType type, Integer scale) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldType", type);
        ReflectionTestUtils.setField(metaField, "scale", scale);
        ReflectionTestUtils.setField(metaField, "label", "Label");
        return metaField;
    }

    /** The model with a string field {@code code} and a two-decimal amount {@code amount}. */
    private static void withMetadata(Runnable body) {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            MetaModel metaModel = new MetaModel();
            ReflectionTestUtils.setField(metaModel, "label", "Order");
            mm.when(() -> ModelManager.getModel(MODEL)).thenReturn(metaModel);
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "code")).thenReturn(field(FieldType.STRING, null));
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "amount")).thenReturn(field(FieldType.BIG_DECIMAL, 2));
            body.run();
        }
    }

    private static ExcelSheetData dynamicallyExported(List<String> fields) {
        ExportByDynamic strategy = new ExportByDynamic();
        ExportDataFetcher fetcher = mock(ExportDataFetcher.class);
        when(fetcher.fetchRows(anyString(), any(), any(FlexQuery.class))).thenReturn(List.of(Map.of()));
        ExcelUploadService uploadService = mock(ExcelUploadService.class);
        ReflectionTestUtils.setField(strategy, "exportDataFetcher", fetcher);
        ReflectionTestUtils.setField(strategy, "excelUploadService", uploadService);
        FlexQuery flexQuery = new FlexQuery();
        flexQuery.setFields(fields);

        strategy.export(MODEL, flexQuery);

        ArgumentCaptor<ExcelSheetData> sheetData = ArgumentCaptor.forClass(ExcelSheetData.class);
        verify(uploadService).generateFileAndUpload(anyString(), anyString(), sheetData.capture());
        return sheetData.getValue();
    }

    @Test
    void theDynamicExportFormatsItsDecimalColumns() {
        withMetadata(() -> {
            WriteHandler[] handlers = dynamicallyExported(List.of("code", "amount")).getWriteHandlers();
            assertThat(handlers).singleElement().isInstanceOf(DecimalColumnFormatHandler.class);
        });
    }

    @Test
    void aDynamicExportWithoutADecimalColumnRegistersNoFormatHandler() {
        withMetadata(() -> {
            WriteHandler[] handlers = dynamicallyExported(List.of("code")).getWriteHandlers();
            assertThat(handlers).as("one slot, left null for the sheet builder to skip").containsExactly((WriteHandler) null);
        });
    }

    @Test
    void theTemplateExportFormatsItsDecimalColumnsBesideItsOwnStyling() {
        withMetadata(() -> {
            ExportByFieldTemplate strategy = new ExportByFieldTemplate();
            ExportTemplateResolver resolver = mock(ExportTemplateResolver.class);
            // "amount" is fetched and exported; "code" is fetched for the rows but ignored in the file,
            // so the exported fields — the sheet's columns — are the amount alone.
            when(resolver.resolve(any(ExportTemplate.class)))
                    .thenReturn(new ResolvedTemplateSheet(List.of("Amount"), List.of("code", "amount"), List.of("amount")));
            when(resolver.resolveRows(any(), any())).thenReturn(List.of());
            ExportDataFetcher fetcher = mock(ExportDataFetcher.class);
            when(fetcher.fetchRows(anyString(), any(), any(FlexQuery.class))).thenReturn(List.of());
            ExcelUploadService uploadService = mock(ExcelUploadService.class);
            ReflectionTestUtils.setField(strategy, "exportTemplateResolver", resolver);
            ReflectionTestUtils.setField(strategy, "exportDataFetcher", fetcher);
            ReflectionTestUtils.setField(strategy, "excelUploadService", uploadService);
            ExportTemplate exportTemplate = new ExportTemplate();
            exportTemplate.setModelName(MODEL);
            exportTemplate.setFileName("orders");

            strategy.export(exportTemplate, new FlexQuery());

            ArgumentCaptor<ExcelSheetData> sheetData = ArgumentCaptor.forClass(ExcelSheetData.class);
            verify(uploadService).generateFileAndUpload(anyString(), anyString(), sheetData.capture());
            WriteHandler[] handlers = sheetData.getValue().getWriteHandlers();
            assertThat(handlers).hasSize(2);
            assertThat(handlers[0]).isInstanceOf(CustomExportStyleHandler.class);
            assertThat(handlers[1]).isInstanceOf(DecimalColumnFormatHandler.class);
        });
    }
}
