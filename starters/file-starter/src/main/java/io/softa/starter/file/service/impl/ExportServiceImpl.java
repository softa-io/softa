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
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.file.dto.ExportResult;
import io.softa.starter.file.dto.ExportTemplateDTO;
import io.softa.starter.file.dto.SheetInfo;
import io.softa.starter.file.entity.ExportHistory;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.excel.export.strategy.ExportByDynamic;
import io.softa.starter.file.excel.export.support.ExcelUploadService;
import io.softa.starter.file.excel.export.support.ImportTemplateExport;
import io.softa.starter.file.excel.export.strategy.ExportByFieldTemplate;
import io.softa.starter.file.excel.export.strategy.ExportContext;
import io.softa.starter.file.excel.export.strategy.ExportStrategyFactory;
import io.softa.starter.file.service.ExportHistoryService;
import io.softa.starter.file.service.ExportService;
import io.softa.starter.file.service.ExportTemplateService;
import io.softa.starter.file.vo.ExportableImportTemplate;
import io.softa.starter.file.vo.PivotSpec;

@Service
public class ExportServiceImpl implements ExportService {

    @Autowired
    private ExportByDynamic exportByDynamic;

    @Autowired
    private ExportByFieldTemplate exportByFieldTemplate;

    @Autowired
    private ExportStrategyFactory exportStrategyFactory;

    @Autowired
    private ExportTemplateService exportTemplateService;

    @Autowired
    private ExportHistoryService exportHistoryService;

    @Autowired
    private ImportTemplateExport importTemplateExport;

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
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param modelName the model name to be exported
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    public FileInfo dynamicExport(String modelName, FlexQuery flexQuery) {
        return dynamicExport(modelName, flexQuery, null);
    }

    @Override
    public FileInfo dynamicExport(String modelName, FlexQuery flexQuery, PivotSpec pivot) {
        long startNanos = System.nanoTime();
        ExportContext context = ExportContext.dynamic(modelName, flexQuery, pivot);
        ExportResult exportResult = exportStrategyFactory.getStrategy(context).export(context);
        this.generateExportHistory(null, modelName, exportResult, startNanos);
        return exportResult.getFileInfo();
    }

    /**
     * Export multiple sheets of data by dynamic fields and ExportParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param sheetInfoList the list of sheetInfo objects
     * @return fileInfo object with download URL
     */
    public FileInfo dynamicExportMultiSheet(String fileName, List<SheetInfo> sheetInfoList) {
        Assert.notBlank(fileName, "The file name cannot be empty.");
        Assert.notEmpty(sheetInfoList, "The sheetInfo List cannot be empty.");
        // Sheet names must be unique — as Excel will store them, not as they were handed in. A name
        // over 31 characters or carrying []:*?/\\ is altered on the way into the workbook, so two
        // that differ only past that point would pass a check on the raw text and then collide.
        //
        // Checked on the effective name too: a blank one falls back to the model name, which is the
        // natural way to ask for one sheet per object. Comparing the blanks themselves made two such
        // sheets look like a conflict with each other.
        List<String> sheetNames = new ArrayList<>();
        sheetInfoList.forEach(sheetInfo -> {
            Assert.isTrue(StringUtils.isNotBlank(sheetInfo.getModelName()),
                    "The model name cannot be empty in the sheetInfo of `{0}`", fileName);
            sheetNames.add(ExcelUploadService.safeSheetName(
                    StringUtils.isNotBlank(sheetInfo.getSheetName())
                            ? sheetInfo.getSheetName() : sheetInfo.getModelName()));
        });
        Assert.isTrue(sheetNames.size() == new HashSet<>(sheetNames).size(),
                "Sheet names in the sheetInfoList must be unique. The sheet names are: {0}", sheetNames);
        return exportByDynamic.exportMultiSheet(fileName, sheetInfoList);
    }

    /**
     * Export data by exportTemplate configured exported fields.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param exportTemplateId the ID of the export template
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    public FileInfo exportByTemplate(Long exportTemplateId, FlexQuery flexQuery) {
        ExportTemplate exportTemplate = exportTemplateService.getById(exportTemplateId)
                .orElseThrow(() -> new IllegalArgumentException("The export template does not exist."));
        this.validateExportTemplate(exportTemplate);
        long startNanos = System.nanoTime();
        if (Boolean.TRUE.equals(exportTemplate.getCustomFileTemplate())) {
            Assert.notNull(exportTemplate.getFileId(), "The export template does not have a custom file template.");
        }
        ExportContext exportContext = ExportContext.template(exportTemplate, flexQuery);
        ExportResult exportResult = exportStrategyFactory.getStrategy(exportContext).export(exportContext);
        this.generateExportHistory(exportTemplate.getId(), exportTemplate.getModelName(), exportResult, startNanos);
        return exportResult.getFileInfo();
    }

    @Override
    public List<ExportableImportTemplate> listImportTemplates(String modelName) {
        return importTemplateExport.listFor(modelName);
    }

    @Override
    public FileInfo exportByImportTemplate(String modelName, Long importTemplateId, FlexQuery flexQuery) {
        long startNanos = System.nanoTime();
        ExportResult exportResult = importTemplateExport.export(modelName, importTemplateId, flexQuery);
        ExportHistory exportHistory = this.exportHistory(null, modelName, exportResult, startNanos);
        exportHistory.setImportTemplateId(importTemplateId);
        exportHistoryService.createOne(exportHistory);
        return exportResult.getFileInfo();
    }

    /**
     * Export multiple sheets merged to on Excel file by specifying multi export templates.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param ids the list of export template id
     * @return fileInfo object with download URL
     */
    public FileInfo exportByMultiTemplate(String fileName, List<Long> ids) {
        List<ExportTemplate> exportTemplates = this.getExportTemplates(ids);
        return exportByFieldTemplate.exportMultiSheet(fileName, exportTemplates);
    }

    public FileInfo dynamicExportByMultiTemplate(String fileName, List<ExportTemplateDTO> dtoList) {
        Map<Long, Filters> dynamicTemplateMap = dtoList.stream().collect(Collectors.toMap(ExportTemplateDTO::getTemplateId, ExportTemplateDTO::getFilters));
        List<ExportTemplate> exportTemplates = this.getExportTemplates(new ArrayList<>(dynamicTemplateMap.keySet()));
        return exportByFieldTemplate.dynamicExportMultiSheet(fileName, exportTemplates, dynamicTemplateMap);
    }

    private List<ExportTemplate> getExportTemplates(List<Long> ids) {
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

    private void generateExportHistory(Long exportTemplateId, String modelName, ExportResult exportResult, long startNanos) {
        exportHistoryService.createOne(this.exportHistory(exportTemplateId, modelName, exportResult, startNanos));
    }

    private ExportHistory exportHistory(Long exportTemplateId, String modelName, ExportResult exportResult, long startNanos) {
        ExportHistory exportHistory = new ExportHistory();
        exportHistory.setTemplateId(exportTemplateId);
        exportHistory.setModelName(modelName);
        exportHistory.setExportedFileId(exportResult.getFileInfo().getFileId());
        exportHistory.setTotalRows(exportResult.getTotalRows());
        exportHistory.setDuration(DateUtils.elapsedSeconds(startNanos));
        return exportHistory;
    }
}
