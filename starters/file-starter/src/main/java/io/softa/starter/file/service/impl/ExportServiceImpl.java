package io.softa.starter.file.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.file.dto.ExportTemplateDTO;
import io.softa.starter.file.dto.SheetInfo;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.excel.ExportByDynamic;
import io.softa.starter.file.excel.ExportByFileTemplate;
import io.softa.starter.file.excel.ExportByTemplate;
import io.softa.starter.file.service.ExportService;
import io.softa.starter.file.service.ExportTemplateService;

@Service
public class ExportServiceImpl implements ExportService {

    @Autowired
    private ExportByDynamic exportByDynamic;

    @Autowired
    private ExportByTemplate exportByTemplate;

    @Autowired
    private ExportByFileTemplate exportByFileTemplate;

    @Autowired
    private ExportTemplateService exportTemplateService;

    /**
     * Validate the exported by file template configuration.
     *
     * @param exportTemplate the export template to be validated
     */
    protected void validateExportTemplate(ExportTemplate exportTemplate) {
        Assert.isTrue(StringUtils.isNotBlank(exportTemplate.getModelName()),
                "The model name in the export template cannot be empty.");
    }

    /**
     * Export data by dynamic fields and ExportParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and itemName for Option fields.
     *
     * @param modelName the model name to be exported
     * @param fileName the name of the Excel file to be generated
     * @param sheetName the name of the sheet in the Excel file
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    public FileInfo dynamicExport(String modelName, String fileName, String sheetName, FlexQuery flexQuery) {
        return exportByDynamic.export(modelName, fileName, sheetName, flexQuery);
    }

    /**
     * Export multiple sheets of data by dynamic fields and ExportParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and itemName for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param sheetInfoList the list of sheetInfo objects
     * @return fileInfo object with download URL
     */
    public FileInfo dynamicExportMultiSheet(String fileName, List<SheetInfo> sheetInfoList) {
        Assert.notBlank(fileName, "The file name cannot be empty.");
        Assert.notEmpty(sheetInfoList, "The sheetInfo List cannot be empty.");
        // Validate the sheetInfoList, sheetNames must be unique
        List<String> sheetNames = new ArrayList<>();
        sheetInfoList.forEach(sheetInfo -> {
            Assert.isTrue(StringUtils.isNotBlank(sheetInfo.getModelName()),
                    "The model name cannot be empty in the sheetInfo of `{0}`", fileName);
            sheetNames.add(sheetInfo.getSheetName());
        });
        Assert.isTrue(sheetNames.size() == new HashSet<>(sheetNames).size(),
                "Sheet names in the sheetInfoList must be unique. The sheet names are: {0}", sheetNames);
        return exportByDynamic.exportMultiSheet(fileName, sheetInfoList);
    }

    /**
     * Export data by exportTemplate configured exported fields.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and itemName for Option fields.
     *
     * @param exportTemplateId the ID of the export template
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    public FileInfo exportByTemplate(String exportTemplateId, FlexQuery flexQuery) {
        ExportTemplate exportTemplate = exportTemplateService.getById(exportTemplateId)
                .orElseThrow(() -> new IllegalArgumentException("The export template does not exist."));
        this.validateExportTemplate(exportTemplate);
        return exportByTemplate.export(exportTemplate, flexQuery);
    }

    /**
     * Export multiple sheets merged to on Excel file by specifying multi export templates.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and itemName for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param ids the list of export template id
     * @return fileInfo object with download URL
     */
    public FileInfo exportByMultiTemplate(String fileName, List<String> ids) {
        List<ExportTemplate> exportTemplates = this.getExportTemplates(ids);
        return exportByTemplate.exportMultiSheet(fileName, exportTemplates);
    }

    public FileInfo dynamicExportByMultiTemplate(String fileName, List<ExportTemplateDTO> dtoList) {
        Map<String, Filters> dynamicTemplateMap = dtoList.stream().collect(Collectors.toMap(ExportTemplateDTO::getTemplateId, ExportTemplateDTO::getFilters));
        List<ExportTemplate> exportTemplates = this.getExportTemplates(new ArrayList<>(dynamicTemplateMap.keySet()));
        return exportByTemplate.dynamicExportMultiSheet(fileName, exportTemplates, dynamicTemplateMap);
    }

    private List<ExportTemplate> getExportTemplates(List<String> ids) {
        List<ExportTemplate> exportTemplates = exportTemplateService.getByIds(ids);
        List<String> sheetNames = new ArrayList<>();
        exportTemplates.forEach(template -> {
            String sheetName = StringUtils.isNotBlank(template.getSheetName()) ? template.getSheetName()
                    : template.getFileName();
            sheetNames.add(sheetName);
            this.validateExportTemplate(template);
        });
        Assert.isTrue(sheetNames.size() == new HashSet<>(sheetNames).size(),
                "The excel sheet name must be unique! {0}", sheetNames);
        return exportTemplates;
    }

    /**
     * Export one or multiple rows of data by file template.
     * The file template is a template file that contains the variables to be filled in.
     *
     * @param exportTemplateId the ID of the export template
     * @param flexQuery the flexQuery of the exported conditions
     * @return fileInfo object with download URL
     */
    public FileInfo exportByFileTemplate(String exportTemplateId, FlexQuery flexQuery) {
        ExportTemplate exportTemplate = exportTemplateService.getById(exportTemplateId)
                .orElseThrow(() -> new IllegalArgumentException("The export template does not exist."));
        this.validateExportTemplate(exportTemplate);
        Assert.notBlank(exportTemplate.getFileId(), "The export template does not have a file template.");
        return exportByFileTemplate.export(exportTemplate, flexQuery);
    }
}
