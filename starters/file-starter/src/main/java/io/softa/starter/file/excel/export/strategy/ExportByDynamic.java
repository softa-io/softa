package io.softa.starter.file.excel.export.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fesod.sheet.write.handler.WriteHandler;
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
import io.softa.starter.file.vo.PivotSpec;
import io.softa.starter.file.excel.export.support.PivotColumns;
import io.softa.starter.file.excel.export.support.ExcelUploadService;
import io.softa.starter.file.excel.export.support.ExportDataFetcher;
import io.softa.starter.file.excel.style.DecimalColumnFormatHandler;

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
    @Autowired
    private PivotColumns pivotColumns;

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
        WriteHandler[] writeHandlers = columnHandlers(modelName, flexQuery);
        List<List<Object>> rowsTable = this.extractDataTableFromDB(modelName, flexQuery, headers);
        String modelLabel = ModelManager.getModel(modelName).getLabel();
        ExcelSheetData sheetData = new ExcelSheetData(modelLabel, headers, rowsTable, writeHandlers);
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
        if (exportContext.getPivot() != null) {
            return exportWithPivot(exportContext.getModelName(), exportContext.getFlexQuery(), exportContext.getPivot());
        }
        return export(exportContext.getModelName(), exportContext.getFlexQuery());
    }

    /**
     * The dynamic export plus pivot columns — see {@link PivotColumns}. The exported rows are fetched
     * once and kept, because the pivot needs their ids to join the source rows on; the plain path
     * discards them after tabulating.
     */
    ExportResult exportWithPivot(String modelName, FlexQuery flexQuery, PivotSpec pivot) {
        List<String> headers = new ArrayList<>();
        WriteHandler[] writeHandlers = columnHandlers(modelName, flexQuery);
        List<List<Object>> rowsTable = extractDataTableWithPivot(modelName, flexQuery, pivot, headers);
        String modelLabel = ModelManager.getModel(modelName).getLabel();
        ExcelSheetData sheetData = new ExcelSheetData(modelLabel, headers, rowsTable, writeHandlers);
        FileInfo fileInfo = excelUploadService.generateFileAndUpload(HISTORY_MODEL, modelLabel, sheetData);
        return new ExportResult(fileInfo, rowsTable.size());
    }

    /** {@link #extractDataTableFromDB} plus the pivot's columns — the rows are kept for their ids. */
    List<List<Object>> extractDataTableWithPivot(String modelName, FlexQuery flexQuery, PivotSpec pivot,
                                                 List<String> headers) {
        List<String> exportedFields = List.copyOf(flexQuery.getFields());
        List<Map<String, Object>> rows = exportDataFetcher.fetchRows(modelName, null, flexQuery);
        exportedFields.forEach(fieldName -> headers.add(ModelManager.getLastFieldOfCascaded(modelName, fieldName).getLabel()));
        List<List<Object>> rowsTable = new ArrayList<>();
        for (List<Object> row : ListUtils.convertToTableData(exportedFields, rows)) {
            rowsTable.add(new ArrayList<>(row));
        }
        pivotColumns.append(modelName, pivot, rows, headers, rowsTable);
        return rowsTable;
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
            WriteHandler[] writeHandlers = columnHandlers(sheetInfo.getModelName(), sheetInfo.getFlexQuery());
            List<List<Object>> rowsTable = sheetInfo.getPivot() != null
                    ? this.extractDataTableWithPivot(sheetInfo.getModelName(), sheetInfo.getFlexQuery(), sheetInfo.getPivot(), headers)
                    : this.extractDataTableFromDB(sheetInfo.getModelName(), sheetInfo.getFlexQuery(), headers);
            String sheetName = StringUtils.isNotBlank(sheetInfo.getSheetName()) ? sheetInfo.getSheetName()
                    : sheetInfo.getModelName();
            sheetDataList.add(new ExcelSheetData(sheetName, headers, rowsTable, writeHandlers));
        }
        // Multi-sheet exports record no history, so this file is attached to nothing and stays
        // unclaimed. Stamped anyway: an unclaimed record with a blank model may be claimed by ANY
        // model, and "claimable by nobody in particular" is not what we want to leave lying around.
        return excelUploadService.generateFileAndUpload(HISTORY_MODEL, fileName, sheetDataList);
    }

    /**
     * The handlers for the sheet's columns: the decimal columns' number formats, or nothing when the
     * sheet has none (the sheet builder skips a null handler).
     *
     * <p>Read before the query for the reason {@link #extractDataTableFromDB} gives: the fields the
     * query leaves behind are no longer the columns in their order. A pivot's columns come after the
     * fields, so the fields' indexes hold either way.
     */
    private static WriteHandler[] columnHandlers(String modelName, FlexQuery flexQuery) {
        List<String> exportedFields = List.copyOf(flexQuery.getFields());
        return new WriteHandler[]{DecimalColumnFormatHandler.forFields(modelName, exportedFields)};
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
