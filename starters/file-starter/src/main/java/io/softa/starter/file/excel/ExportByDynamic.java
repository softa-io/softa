package io.softa.starter.file.excel;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.springframework.stereotype.Component;

import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.ListUtils;
import io.softa.starter.file.dto.ExcelDataDTO;
import io.softa.starter.file.dto.SheetInfo;

/**
 * Export by dynamic parameters
 */
@Slf4j
@Component
public class ExportByDynamic extends CommonExport {

    /**
     * Export data by dynamic fields and QueryParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and itemName for Option fields.
     *
     * @param modelName the model name to be exported
     * @param fileName the name of the Excel file to be generated
     * @param sheetName the name of the sheet in the Excel file
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    public FileInfo export(String modelName, String fileName, String sheetName, FlexQuery flexQuery) {
        // Get the data to be exported
        List<String> headers = new ArrayList<>();
        List<List<Object>> rowsTable = this.extractDataTableFromDB(modelName, flexQuery, headers);
        // Generate the Excel file
        String modelLabel = ModelManager.getModel(modelName).getLabelName();
        fileName = StringUtils.isNotBlank(fileName) ? fileName : modelLabel;
        sheetName = StringUtils.isNotBlank(sheetName) ? sheetName : fileName;
        // Excel data DTO
        ExcelDataDTO excelDataDTO = new ExcelDataDTO();
        excelDataDTO.setFileName(fileName);
        excelDataDTO.setSheetName(sheetName);
        excelDataDTO.setHeaders(headers);
        excelDataDTO.setRowsTable(rowsTable);
        FileInfo fileInfo = this.generateFileAndUpload(modelName, excelDataDTO);
        // Generate an export history record
        this.generateExportHistory(null, fileInfo.getFileId());
        return fileInfo;
    }

    /**
     * Export multiple sheets of data by dynamic fields and QueryParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and itemName for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param sheetInfoList the list of sheetInfo objects
     * @return fileInfo object with download URL
     */
    public FileInfo exportMultiSheet(String fileName, List<SheetInfo> sheetInfoList) {
        FileInfo fileInfo;
        // Generate the Excel file
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             // Use FesodSheet to write the file with dynamic headers and data
             ExcelWriter excelWriter = FesodSheet.write(outputStream).build()) {
            for (int i = 0; i < sheetInfoList.size(); i++) {
                SheetInfo sheetInfo = sheetInfoList.get(i);
                List<String> headers = new ArrayList<>();
                List<List<Object>> rowsTable = this.extractDataTableFromDB(sheetInfo.getModelName(), sheetInfo.getFlexQuery(), headers);
                // Write the header and data, FesodSheet requires the header to be a list of lists
                List<List<String>> headerList = headers.stream().map(Collections::singletonList).toList();
                String sheetName = StringUtils.isNotBlank(sheetInfo.getSheetName()) ? sheetInfo.getSheetName()
                        : sheetInfo.getModelName();
                WriteSheet writeSheet = FesodSheet.writerSheet(i, sheetName).head(headerList).build();
                excelWriter.write(rowsTable, writeSheet);
            }
            excelWriter.finish();
            // upload the Excel bytes to the file storage
            byte[] excelBytes = outputStream.toByteArray();
            fileInfo = this.uploadExcelBytes(StringConstant.EMPTY_STRING, fileName, excelBytes);
        } catch (Exception e) {
            throw new BusinessException("Error generating Excel {0} with the provided data.", fileName, e);
        }
        // Generate an export history record
        this.generateExportHistory(null, fileInfo.getFileId());
        return fileInfo;
    }

    /**
     * Extract the data table from the database by the given model name and flexQuery.
     * And extract the header list from the model fields.
     *
     * @param modelName the model name to be exported
     * @param flexQuery the flexQuery object
     * @param headers the list of header label
     */
    private List<List<Object>> extractDataTableFromDB(String modelName, FlexQuery flexQuery, List<String> headers) {
        // Get the data to be exported
        List<Map<String, Object>> rows = this.getExportedRows(modelName, null, flexQuery);
        // Construct the headers order by sequence of the fields
        List<String> fieldNames = flexQuery.getFields();
        fieldNames.forEach(fieldName -> {
            MetaField lastField = ModelManager.getLastFieldOfCascaded(modelName, fieldName);
            headers.add(lastField.getLabelName());
        });
        return ListUtils.convertToTableData(fieldNames, rows);
    }
}
