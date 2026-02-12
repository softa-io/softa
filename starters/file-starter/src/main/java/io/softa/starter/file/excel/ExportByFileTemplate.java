package io.softa.starter.file.excel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import cn.idev.excel.ExcelWriter;
import cn.idev.excel.FastExcel;
import cn.idev.excel.write.metadata.WriteSheet;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.file.entity.ExportTemplate;

/**
 * Export by file template.
 * Export data based on the uploaded template file
 */
@Slf4j
@Component
public class ExportByFileTemplate extends CommonExport {

    @Autowired
    private FileService fileService;

    /**
     * Export data rows by file template.
     * The file template is a template file that contains the variables to be filled in.
     *
     * @param exportTemplate exportTemplate object
     * @param flexQuery the flexQuery of the exported conditions
     * @return fileInfo object with download URL
     */
    public FileInfo export(ExportTemplate exportTemplate, FlexQuery flexQuery) {
        // TODO: cache the extracted fields in the exportTemplate
        Set<String> fields = extractVariablesOfFileTemplate(exportTemplate.getFileId());
        flexQuery.setFields(fields);
        List<Map<String, Object>> rows = this.getExportedRows(exportTemplate.getModelName(),
                exportTemplate.getCustomHandler(), flexQuery);
        // Fill in the data into the file template
        FileInfo fileInfo = this.generateByFileTemplateAndUpload(exportTemplate, rows);
        // Generate export history
        this.generateExportHistory(exportTemplate.getId(), fileInfo.getFileId());
        return fileInfo;
    }

    /**
     * Renders data into a file template and uploads the generated file to OSS.
     *
     * @param exportTemplate the export template object
     * @param rows the data to be filled into the file template
     * @return fileInfo object with download URL
     */
    private FileInfo generateByFileTemplateAndUpload(ExportTemplate exportTemplate, List<Map<String, Object>> rows) {
        String fileName = exportTemplate.getFileName();
        String sheetName = StringUtils.isNotBlank(exportTemplate.getSheetName()) ? exportTemplate.getSheetName()
                : fileName;
        try (InputStream inputStream = fileService.downloadStream(exportTemplate.getFileId());
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             // Use FastExcel to write the template and fill in the data
             ExcelWriter excelWriter = FastExcel.write(outputStream).withTemplate(inputStream).build()) {
            // Create a write sheet and fill in the data
            WriteSheet writeSheet = FastExcel.writerSheet(sheetName).build();
            excelWriter.fill(rows, writeSheet);
            // TODO: fill in the ENV related to current user
            excelWriter.finish();
            // Upload the Excel stream to OSS
            byte[] excelBytes = outputStream.toByteArray();
            return this.uploadExcelBytes(exportTemplate.getModelName(), fileName, excelBytes);
        } catch (Exception e) {
            throw new BusinessException("Failed to fill data into the file template {}.", fileName, e);
        }
    }

    /**
     * Get all the variable parameters in the file template.
     *
     * @param fileId the ID of the file template
     * @return all variables in the template
     */
    private Set<String> extractVariablesOfFileTemplate(Long fileId) {
        try (InputStream inputStream = fileService.downloadStream(fileId);
             Workbook workbook = new XSSFWorkbook(inputStream)) {
            return getVariablesInWorkbook(workbook);
        } catch (IOException e) {
            throw new BusinessException("Failed to read the file template.", e);
        }
    }

    /**
     * Get all the variable parameters in the Workbook (format: {variable})
     * Cases of variable extraction:
     *      - `{name}` -> `name`
     *      - `{  name }` -> `name`
     *      - `Hello, {name123}!` -> `name123`
     *      - `{name}, {orderNumber}` -> `name`, `orderNumber`
     *      - `{deptId.name}` -> `deptId.name`
     *      - `Hello, {name}, {}` -> `name`
     *      - `Hello, {name}, \{id\}` -> `name`
     *
     * @param workbook workbook object
     * @return all variables in the template
     */
    private static Set<String> getVariablesInWorkbook(Workbook workbook) {
        Set<String> variables = new HashSet<>();
        // Iterate over all sheets
        for(Sheet sheet : workbook) {
            // Iterate over all rows in each sheet
            for(Row row : sheet) {
                // Iterate over all cells in each row
                for(Cell cell : row) {
                    if(cell.getCellType() == CellType.STRING) {
                        String cellValue = cell.getStringCellValue();
                        // Use regular expressions to match the variable format ${variable}
                        Pattern pattern = Pattern.compile("\\{\\s*([a-zA-Z0-9_.]+)\\s*}");
                        Matcher matcher = pattern.matcher(cellValue);
                        while(matcher.find()) {
                            variables.add(matcher.group());
                        }
                    }
                }
            }
        }
        return variables;
    }

}
