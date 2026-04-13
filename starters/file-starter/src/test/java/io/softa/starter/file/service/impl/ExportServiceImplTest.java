package io.softa.starter.file.service.impl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.file.dto.ExportResult;
import io.softa.starter.file.dto.ExportTemplateDTO;
import io.softa.starter.file.dto.SheetInfo;
import io.softa.starter.file.entity.ExportHistory;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.excel.export.strategy.*;
import io.softa.starter.file.service.ExportHistoryService;
import io.softa.starter.file.service.ExportTemplateService;

import static org.junit.jupiter.api.Assertions.*;

class ExportServiceImplTest {

    @Test
    void dynamicExportCreatesHistoryWithMetrics() {
        RecordingExportHistoryService historyService = new RecordingExportHistoryService();
        StubExportByDynamic exportByDynamic = new StubExportByDynamic();
        exportByDynamic.singleResult = exportResult(101L, 3);

        ExportServiceImpl exportService = createService(exportByDynamic, new StubExportByFieldTemplate(),
                new StubExportByFileTemplate(), templateService(Map.of(), Map.of()), historyService.proxy());

        exportService.dynamicExport("demo.model", new FlexQuery());

        assertEquals(1, historyService.histories.size());
        ExportHistory exportHistory = historyService.histories.getFirst();
        assertEquals("demo.model", exportHistory.getModelName());
        assertEquals(101L, exportHistory.getExportedFileId());
        assertEquals(3, exportHistory.getTotalRows());
        assertNotNull(exportHistory.getDuration());
    }

    @Test
    void exportByTemplateCreatesHistoryForConfiguredTemplate() {
        RecordingExportHistoryService historyService = new RecordingExportHistoryService();
        StubExportByFieldTemplate exportByTemplate = new StubExportByFieldTemplate();
        exportByTemplate.singleResult = exportResult(202L, 5);
        ExportTemplate exportTemplateEntity = exportTemplate(11L, "order", false, null, "report", "Sheet1");

        ExportServiceImpl exportService = createService(new StubExportByDynamic(), exportByTemplate,
                new StubExportByFileTemplate(),
                templateService(Map.of(11L, exportTemplateEntity), Map.of()), historyService.proxy());

        exportService.exportByTemplate(11L, new FlexQuery());

        assertEquals(1, historyService.histories.size());
        ExportHistory exportHistory = historyService.histories.getFirst();
        assertEquals(11L, exportHistory.getTemplateId());
        assertEquals("order", exportHistory.getModelName());
        assertEquals(202L, exportHistory.getExportedFileId());
        assertEquals(5, exportHistory.getTotalRows());
    }

    @Test
    void exportByTemplateCreatesHistoryForCustomFileTemplate() {
        RecordingExportHistoryService historyService = new RecordingExportHistoryService();
        StubExportByFileTemplate exportByFileTemplate = new StubExportByFileTemplate();
        exportByFileTemplate.singleResult = exportResult(303L, 2);
        ExportTemplate exportTemplateEntity = exportTemplate(12L, "invoice", true, 88L, "invoice", "Invoice");

        ExportServiceImpl exportService = createService(new StubExportByDynamic(), new StubExportByFieldTemplate(),
                exportByFileTemplate, templateService(Map.of(12L, exportTemplateEntity), Map.of()),
                historyService.proxy());

        exportService.exportByTemplate(12L, new FlexQuery());

        assertEquals(1, historyService.histories.size());
        ExportHistory exportHistory = historyService.histories.getFirst();
        assertEquals(12L, exportHistory.getTemplateId());
        assertEquals("invoice", exportHistory.getModelName());
        assertEquals(303L, exportHistory.getExportedFileId());
        assertEquals(2, exportHistory.getTotalRows());
    }

    @Test
    void dynamicExportMultiSheetDoesNotCreateHistory() {
        RecordingExportHistoryService historyService = new RecordingExportHistoryService();
        StubExportByDynamic exportByDynamic = new StubExportByDynamic();
        exportByDynamic.multiResult = new FileInfo();
        ExportServiceImpl exportService = createService(exportByDynamic, new StubExportByFieldTemplate(),
                new StubExportByFileTemplate(), templateService(Map.of(), Map.of()), historyService.proxy());

        SheetInfo sheetInfo = new SheetInfo();
        sheetInfo.setModelName("demo.model");
        sheetInfo.setSheetName("Sheet1");
        sheetInfo.setFlexQuery(new FlexQuery());
        exportService.dynamicExportMultiSheet("report", List.of(sheetInfo));

        assertTrue(historyService.histories.isEmpty());
    }

    @Test
    void exportByMultiTemplateDoesNotCreateHistory() {
        RecordingExportHistoryService historyService = new RecordingExportHistoryService();
        StubExportByFieldTemplate exportByTemplate = new StubExportByFieldTemplate();
        exportByTemplate.multiResult = new FileInfo();
        ExportTemplate exportTemplateEntity = exportTemplate(21L, "demo.model", false, null, "report", "Sheet1");

        ExportServiceImpl exportService = createService(new StubExportByDynamic(), exportByTemplate,
                new StubExportByFileTemplate(),
                templateService(Map.of(), Map.of(List.of(21L), List.of(exportTemplateEntity))), historyService.proxy());

        exportService.exportByMultiTemplate("report", List.of(21L));

        assertTrue(historyService.histories.isEmpty());
    }

    @Test
    void dynamicExportByMultiTemplateDoesNotCreateHistory() {
        RecordingExportHistoryService historyService = new RecordingExportHistoryService();
        StubExportByFieldTemplate exportByTemplate = new StubExportByFieldTemplate();
        exportByTemplate.dynamicMultiResult = new FileInfo();
        ExportTemplate exportTemplateEntity = exportTemplate(31L, "demo.model", false, null, "report", "Sheet1");

        ExportServiceImpl exportService = createService(new StubExportByDynamic(), exportByTemplate,
                new StubExportByFileTemplate(),
                templateService(Map.of(), Map.of(List.of(31L), List.of(exportTemplateEntity))), historyService.proxy());

        ExportTemplateDTO exportTemplateDTO = new ExportTemplateDTO();
        exportTemplateDTO.setTemplateId(31L);
        exportTemplateDTO.setFilters(new Filters());
        exportService.dynamicExportByMultiTemplate("report", List.of(exportTemplateDTO));

        assertTrue(historyService.histories.isEmpty());
    }

    private ExportServiceImpl createService(ExportByDynamic exportByDynamic, ExportByFieldTemplate exportByFieldTemplate,
                                            ExportByFileTemplate exportByFileTemplate,
                                            ExportTemplateService exportTemplateService,
                                            ExportHistoryService exportHistoryService) {
        ExportServiceImpl exportService = new ExportServiceImpl();
        ReflectionTestUtils.setField(exportService, "exportByDynamic", exportByDynamic);
        ReflectionTestUtils.setField(exportService, "exportByFieldTemplate", exportByFieldTemplate);
        ExportStrategyFactory exportStrategyFactory = new StubExportStrategyFactory(exportByDynamic, exportByFieldTemplate,
                exportByFileTemplate);
        ReflectionTestUtils.setField(exportService, "exportStrategyFactory", exportStrategyFactory);
        ReflectionTestUtils.setField(exportService, "exportTemplateService", exportTemplateService);
        ReflectionTestUtils.setField(exportService, "exportHistoryService", exportHistoryService);
        return exportService;
    }

    private ExportResult exportResult(Long fileId, Integer totalRows) {
        FileInfo fileInfo = new FileInfo();
        fileInfo.setFileId(fileId);
        return new ExportResult(fileInfo, totalRows);
    }

    private ExportTemplate exportTemplate(Long id, String modelName, Boolean customFileTemplate, Long fileId,
                                          String fileName, String sheetName) {
        ExportTemplate exportTemplate = new ExportTemplate();
        exportTemplate.setId(id);
        exportTemplate.setModelName(modelName);
        exportTemplate.setCustomFileTemplate(customFileTemplate);
        exportTemplate.setFileId(fileId);
        exportTemplate.setFileName(fileName);
        exportTemplate.setSheetName(sheetName);
        return exportTemplate;
    }

    private ExportTemplateService templateService(Map<Long, ExportTemplate> templatesById,
                                                  Map<List<Long>, List<ExportTemplate>> templatesByIds) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "toString" -> "StubExportTemplateService";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "getById" -> Optional.ofNullable(templatesById.get(args[0]));
            case "getByIds" -> templatesByIds.getOrDefault(args[0], List.of());
            default -> defaultValue(method.getReturnType());
        };
        return proxy(handler);
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            if (Optional.class.equals(returnType)) {
                return Optional.empty();
            }
            if (List.class.equals(returnType)) {
                return List.of();
            }
            if (Map.class.equals(returnType)) {
                return Map.of();
            }
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return false;
        }
        if (long.class.equals(returnType)) {
            return 0L;
        }
        if (int.class.equals(returnType)) {
            return 0;
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(ExportTemplateService.class.getClassLoader(), new Class<?>[]{ExportTemplateService.class}, handler);
    }

    private static class RecordingExportHistoryService {
        private final List<ExportHistory> histories = new ArrayList<>();

        private ExportHistoryService proxy() {
            InvocationHandler handler = (proxy, method, args) -> {
                switch (method.getName()) {
                    case "toString" -> {
                        return "RecordingExportHistoryService";
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    case "createOne" -> {
                        histories.add((ExportHistory) args[0]);
                        return 1L;
                    }
                }
                return defaultValue(method.getReturnType());
            };
            return proxy(handler);
        }

        private Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                if (Optional.class.equals(returnType)) {
                    return Optional.empty();
                }
                if (List.class.equals(returnType)) {
                    return List.of();
                }
                if (Map.class.equals(returnType)) {
                    return Map.of();
                }
                return null;
            }
            if (boolean.class.equals(returnType)) {
                return false;
            }
            if (long.class.equals(returnType)) {
                return 0L;
            }
            if (int.class.equals(returnType)) {
                return 0;
            }
            return 0;
        }

        @SuppressWarnings("unchecked")
        private <T> T proxy(InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(ExportHistoryService.class.getClassLoader(), new Class<?>[]{ExportHistoryService.class}, handler);
        }
    }

    private static class StubExportByDynamic extends ExportByDynamic {
        private ExportResult singleResult;
        private FileInfo multiResult;

        @Override
        public ExportResult export(String modelName, FlexQuery flexQuery) {
            return singleResult;
        }

        @Override
        public FileInfo exportMultiSheet(String fileName, List<SheetInfo> sheetInfoList) {
            return multiResult;
        }
    }

    private static class StubExportByFieldTemplate extends ExportByFieldTemplate {
        private ExportResult singleResult;
        private FileInfo multiResult;
        private FileInfo dynamicMultiResult;

        @Override
        public ExportResult export(ExportTemplate exportTemplate, FlexQuery flexQuery) {
            return singleResult;
        }

        @Override
        public FileInfo exportMultiSheet(String fileName, List<ExportTemplate> exportTemplates) {
            return multiResult;
        }

        @Override
        public FileInfo dynamicExportMultiSheet(String fileName, List<ExportTemplate> exportTemplates,
                                                Map<Long, Filters> dynamicTemplateMap) {
            return dynamicMultiResult;
        }
    }

    private static class StubExportByFileTemplate extends ExportByFileTemplate {
        private ExportResult singleResult;

        @Override
        public ExportResult export(ExportTemplate exportTemplate, FlexQuery flexQuery) {
            return singleResult;
        }
    }

    /**
     * Test-only stub that avoids reflection-based field injection so factory implementation details do not make the test brittle.
     */
    private static class StubExportStrategyFactory extends ExportStrategyFactory {
        private final ExportByDynamic exportByDynamic;
        private final ExportByFieldTemplate exportByFieldTemplate;
        private final ExportByFileTemplate exportByFileTemplate;

        private StubExportStrategyFactory(ExportByDynamic exportByDynamic, ExportByFieldTemplate exportByFieldTemplate,
                                          ExportByFileTemplate exportByFileTemplate) {
            this.exportByDynamic = exportByDynamic;
            this.exportByFieldTemplate = exportByFieldTemplate;
            this.exportByFileTemplate = exportByFileTemplate;
        }

        @Override
        public ExportStrategy getStrategy(ExportContext exportContext) {
            if (exportContext == null || exportContext.getExportTemplate() == null) {
                return exportByDynamic;
            }
            ExportTemplate exportTemplate = exportContext.getExportTemplate();
            if (Boolean.TRUE.equals(exportTemplate.getCustomFileTemplate())) {
                return exportByFileTemplate;
            }
            return exportByFieldTemplate;
        }
    }
}
