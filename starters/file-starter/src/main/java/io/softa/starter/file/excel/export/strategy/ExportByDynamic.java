package io.softa.starter.file.excel.export.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.utils.ListUtils;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.file.dto.ExportResult;
import io.softa.starter.file.dto.SheetInfo;
import io.softa.starter.file.entity.ExportHistory;
import io.softa.starter.file.excel.export.ExcelSheetData;
import io.softa.starter.file.excel.export.support.ExcelUploadService;
import io.softa.starter.file.excel.export.support.ExportDataFetcher;

/**
 * Export by dynamic parameters
 */
@Slf4j
@Component
public class ExportByDynamic implements ExportStrategy {

    /** The model that HOLDS an exported file — the history row, not the model exported. */
    private static final String HISTORY_MODEL = ExportHistory.class.getSimpleName();

    @Autowired
    private ExportDataFetcher exportDataFetcher;

    @Autowired
    private ExcelUploadService excelUploadService;

    /**
     * Export data by dynamic fields and QueryParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param modelName the model name to be exported
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    public ExportResult export(String modelName, FlexQuery flexQuery) {
        List<String> headers = new ArrayList<>();
        List<List<Object>> rowsTable = this.extractDataTableFromDB(modelName, flexQuery, headers);
        String modelLabel = ModelManager.getModel(modelName).getLabel();
        ExcelSheetData sheetData = new ExcelSheetData(modelLabel, headers, rowsTable, null);
        // Stamped with the model that will HOLD it — the ExportHistory row — not the model whose
        // rows it contains. FileRecord.modelName decides who may claim the file, and stamping the
        // exported model has ExportHistory.fileId refused as "not yours to attach".
        FileInfo fileInfo = excelUploadService.generateFileAndUpload(HISTORY_MODEL, modelLabel, sheetData);
        return new ExportResult(fileInfo, rowsTable.size());
    }

    @Override
    public ExportMode getMode() {
        return ExportMode.DYNAMIC;
    }

    @Override
    public ExportResult export(ExportContext exportContext) {
        return export(exportContext.getModelName(), exportContext.getFlexQuery());
    }

    /**
     * Export multiple sheets of data by dynamic fields and QueryParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param sheetInfoList the list of sheetInfo objects
     * @return fileInfo object with download URL
     */
    public FileInfo exportMultiSheet(String fileName, List<SheetInfo> sheetInfoList) {
        List<ExcelSheetData> sheetDataList = new ArrayList<>();
        for (SheetInfo sheetInfo : sheetInfoList) {
            List<String> headers = new ArrayList<>();
            List<List<Object>> rowsTable = this.extractDataTableFromDB(sheetInfo.getModelName(), sheetInfo.getFlexQuery(), headers);
            String sheetName = StringUtils.isNotBlank(sheetInfo.getSheetName()) ? sheetInfo.getSheetName()
                    : sheetInfo.getModelName();
            sheetDataList.add(new ExcelSheetData(sheetName, headers, rowsTable, null));
        }
        // Multi-sheet exports record no history, so this file is attached to nothing and stays
        // unclaimed. Stamped anyway: an unclaimed record with a blank model may be claimed by ANY
        // model, and "claimable by nobody in particular" is not what we want to leave lying around.
        return excelUploadService.generateFileAndUpload(HISTORY_MODEL, fileName, sheetDataList);
    }

    /**
     * Extract the data table from the database by the given model name and flexQuery.
     * And extract the header list from the model fields.
     *
     * @param modelName the model name to be exported
     * @param flexQuery the flexQuery object
     * @param headers the list of header label
     */
    List<List<Object>> extractDataTableFromDB(String modelName, FlexQuery flexQuery, List<String> headers) {
        // Read BEFORE the query, not after. SelectBuilder writes its own working set back onto the
        // flexQuery — `new HashSet<>(fields)` plus the id and whatever a dynamic field depends on —
        // so the list afterwards is no longer the one that was asked for: the sheet came out in hash
        // order, with the id in it, however the fields had been picked.
        List<String> exportedFields = List.copyOf(flexQuery.getFields());
        List<Map<String, Object>> rows = exportDataFetcher.fetchRows(modelName, null, flexQuery);
        exportedFields.forEach(fieldName -> {
            MetaField lastField = ModelManager.getLastFieldOfCascaded(modelName, fieldName);
            headers.add(lastField.getLabel());
        });
        return ListUtils.convertToTableData(exportedFields, rows);
    }
}
