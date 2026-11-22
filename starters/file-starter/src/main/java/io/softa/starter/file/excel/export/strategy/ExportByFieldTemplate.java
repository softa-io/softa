package io.softa.starter.file.excel.export.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fesod.sheet.write.handler.WriteHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.starter.file.dto.ExportResult;
import io.softa.starter.file.entity.ExportHistory;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.excel.export.ExcelSheetData;
import io.softa.starter.file.excel.export.ResolvedTemplateSheet;
import io.softa.starter.file.excel.export.support.ExcelUploadService;
import io.softa.starter.file.excel.export.support.ExportDataFetcher;
import io.softa.starter.file.excel.export.support.ExportTemplateResolver;
import io.softa.starter.file.excel.style.CustomExportStyleHandler;
import io.softa.starter.file.excel.style.DecimalColumnFormatHandler;

/**
 * Export by template.
 */
@Slf4j
@Component
public class ExportByFieldTemplate implements ExportStrategy {

    /** The model that HOLDS an exported file — the history row, not the model exported. */
    private static final String HISTORY_MODEL = ExportHistory.class.getSimpleName();

    @Autowired
    private ExportTemplateResolver exportTemplateResolver;

    @Autowired
    private ExportDataFetcher exportDataFetcher;

    @Autowired
    private ExcelUploadService excelUploadService;

    /**
     * Export data by exportTemplate configured exported fields.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param exportTemplate exportTemplate object
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    public ExportResult export(ExportTemplate exportTemplate, FlexQuery flexQuery) {
        String fileName = exportTemplate.getFileName();
        String sheetName = exportTemplate.getSheetName();
        ResolvedTemplateSheet resolvedTemplateSheet = exportTemplateResolver.resolve(exportTemplate);
        flexQuery.setFields(resolvedTemplateSheet.getFetchFields());
        List<Map<String, Object>> rows = exportDataFetcher.fetchRows(exportTemplate.getModelName(),
                exportTemplate.getCustomHandler(), flexQuery);
        List<List<Object>> rowsTable = exportTemplateResolver.resolveRows(resolvedTemplateSheet, rows);
        ExcelSheetData sheetData = new ExcelSheetData(StringUtils.isNotBlank(sheetName) ? sheetName : fileName,
                resolvedTemplateSheet.getHeaders(), rowsTable, columnHandlers(exportTemplate, resolvedTemplateSheet));
        // Stamped with the model that will HOLD it — the ExportHistory row — not the model whose
        // rows it contains. FileRecord.modelName decides who may claim the file, and stamping the
        // exported model has ExportHistory.fileId refused as "not yours to attach".
        FileInfo fileInfo = excelUploadService.generateFileAndUpload(HISTORY_MODEL, fileName, sheetData);
        return new ExportResult(fileInfo, rowsTable.size());
    }

    @Override
    public ExportMode getMode() {
        return ExportMode.FIELD_TEMPLATE;
    }

    @Override
    public ExportResult export(ExportContext exportContext) {
        return export(exportContext.getExportTemplate(), exportContext.getFlexQuery());
    }

    /**
     * Export multiple sheets of data by dynamic fields and QueryParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param exportTemplates the list of exportTemplates
     * @return fileInfo object with download URL
     */
    public FileInfo exportMultiSheet(String fileName, List<ExportTemplate> exportTemplates) {
        return this.generateFileInfo(fileName, exportTemplates, Map.of());
    }

    public FileInfo dynamicExportMultiSheet(String fileName, List<ExportTemplate> exportTemplates, Map<Long, Filters> dynamicTemplateMap) {
        return this.generateFileInfo(fileName, exportTemplates, dynamicTemplateMap);
    }

    private FileInfo generateFileInfo(String fileName, List<ExportTemplate> exportTemplates, Map<Long, Filters> dynamicTemplateMap) {
        List<ExcelSheetData> sheetDataList = new ArrayList<>();
        for (ExportTemplate exportTemplate : exportTemplates) {
            ResolvedTemplateSheet resolvedTemplateSheet = exportTemplateResolver.resolve(exportTemplate);
            FlexQuery flexQuery;
            if (dynamicTemplateMap.isEmpty()) {
                flexQuery = new FlexQuery(exportTemplate.getFilters(), exportTemplate.getOrders());
            } else {
                flexQuery = new FlexQuery(exportTemplate.getFilters(), exportTemplate.getOrders());
                flexQuery.setFilters(Filters.and(flexQuery.getFilters(), dynamicTemplateMap.get(exportTemplate.getId())));
            }
            flexQuery.setConvertType(ConvertType.DISPLAY);
            flexQuery.setFields(resolvedTemplateSheet.getFetchFields());
            List<Map<String, Object>> rows = exportDataFetcher.fetchRows(exportTemplate.getModelName(),
                    exportTemplate.getCustomHandler(), flexQuery);
            List<List<Object>> rowsTable = exportTemplateResolver.resolveRows(resolvedTemplateSheet, rows);
            String sheetName = StringUtils.isNotBlank(exportTemplate.getSheetName()) ? exportTemplate.getSheetName() : fileName;
            sheetDataList.add(new ExcelSheetData(sheetName, resolvedTemplateSheet.getHeaders(), rowsTable,
                    columnHandlers(exportTemplate, resolvedTemplateSheet)));
        }
        // Multi-sheet exports record no history, so this file is attached to nothing and stays
        // unclaimed. Stamped anyway: an unclaimed record with a blank model may be claimed by ANY
        // model, and "claimable by nobody in particular" is not what we want to leave lying around.
        return excelUploadService.generateFileAndUpload(HISTORY_MODEL, fileName, sheetDataList);
    }

    /**
     * The handlers for the sheet's columns: the template's own row styling, and the decimal columns'
     * number formats when the template exports any (the sheet builder skips a null handler).
     * The exported fields are positional with the headers — an ignored field is in neither.
     */
    private static WriteHandler[] columnHandlers(ExportTemplate exportTemplate, ResolvedTemplateSheet resolvedTemplateSheet) {
        return new WriteHandler[]{new CustomExportStyleHandler(),
                DecimalColumnFormatHandler.forFields(exportTemplate.getModelName(), resolvedTemplateSheet.getExportFields())};
    }

}
