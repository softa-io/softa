package io.softa.starter.file.excel;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import cn.idev.excel.ExcelWriter;
import cn.idev.excel.FastExcel;
import cn.idev.excel.write.metadata.WriteSheet;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.starter.file.dto.ExcelDataDTO;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.excel.handler.CustomExportStyleHandler;

/**
 * Export by template.
 */
@Slf4j
@Component
public class ExportByTemplate extends CommonExport {

    /**
     * Export data by exportTemplate configured exported fields.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and itemName for Option fields.
     *
     * @param exportTemplate exportTemplate object
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    public FileInfo export(ExportTemplate exportTemplate, FlexQuery flexQuery) {
        String fileName = exportTemplate.getFileName();
        String sheetName = exportTemplate.getSheetName();
        // Excel data DTO
        ExcelDataDTO excelDataDTO = new ExcelDataDTO();
        excelDataDTO.setFileName(fileName);
        excelDataDTO.setSheetName(StringUtils.isNotBlank(sheetName) ? sheetName : fileName);
        // Fill in the headers and rows
        this.fillHeadersByTemplate(exportTemplate, excelDataDTO);
        this.fillRowsByTemplate(exportTemplate, flexQuery, excelDataDTO);
        // Generate the Excel file
        FileInfo fileInfo = this.generateFileAndUpload(exportTemplate.getModelName(), excelDataDTO,
                new CustomExportStyleHandler());
        // Generate an export history record
        this.generateExportHistory(exportTemplate.getId(), fileInfo.getFileId());
        return fileInfo;
    }

    /**
     * Export multiple sheets of data by dynamic fields and QueryParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and itemName for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param exportTemplates the list of exportTemplates
     * @return fileInfo object with download URL
     */
    public FileInfo exportMultiSheet(String fileName, List<ExportTemplate> exportTemplates) {
        return this.getFileInfo(fileName, exportTemplates, Collections.emptyMap());
    }

    public FileInfo dynamicExportMultiSheet(String fileName, List<ExportTemplate> exportTemplates, Map<Long, Filters> dynamicTemplateMap) {
        return this.getFileInfo(fileName, exportTemplates, dynamicTemplateMap);
    }

    private FileInfo getFileInfo(String fileName, List<ExportTemplate> exportTemplates, Map<Long, Filters> dynamicTemplateMap) {
        FileInfo fileInfo;
        // Generate the Excel file
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             // Use FastExcel to write the file with dynamic headers and data
             ExcelWriter excelWriter = FastExcel.write(outputStream).build()) {
            for (int i = 0; i < exportTemplates.size(); i++) {
                ExportTemplate exportTemplate = exportTemplates.get(i);
                String sheetName = exportTemplate.getSheetName();
                // Excel data DTO
                ExcelDataDTO excelDataDTO = new ExcelDataDTO();
                excelDataDTO.setFileName(fileName);
                excelDataDTO.setSheetName(StringUtils.isNotBlank(sheetName) ? sheetName : fileName);
                // Fill in the headers and rows
                this.fillHeadersByTemplate(exportTemplate, excelDataDTO);

                // Get the data to be exported
                FlexQuery flexQuery = new FlexQuery(exportTemplate.getFilters(), exportTemplate.getOrders());
                flexQuery.setConvertType(ConvertType.DISPLAY);
                flexQuery.setFilters(Filters.and(flexQuery.getFilters(), dynamicTemplateMap.get(exportTemplate.getId())));
                this.fillRowsByTemplate(exportTemplate, flexQuery, excelDataDTO);

                // Write the header and data
                List<List<String>> headerList = excelDataDTO.getHeaders().stream().map(Collections::singletonList).toList();
                WriteSheet writeSheet = FastExcel.writerSheet(i, sheetName).head(headerList).registerWriteHandler(new CustomExportStyleHandler()).build();
                excelWriter.write(excelDataDTO.getRowsTable(), writeSheet);
            }
            excelWriter.finish();
            // Upload the Excel stream to OSS
            byte[] excelBytes = outputStream.toByteArray();
            fileInfo = this.uploadExcelBytes(StringConstant.EMPTY_STRING, fileName, excelBytes);
        } catch (Exception e) {
            throw new BusinessException("Error generating Excel {0} with the provided data.", fileName, e);
        }
        // Generate an export history record
        this.generateExportHistory(null, fileInfo.getFileId());
        return fileInfo;
    }

}
