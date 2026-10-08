package io.softa.starter.file.service.impl;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.event.AnalysisEventListener;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.RowWriteHandler;
import org.apache.fesod.sheet.write.handler.WriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.fesod.sheet.write.handler.context.RowWriteHandlerContext;
import org.apache.fesod.sheet.write.metadata.style.WriteCellStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.web.multipart.MultipartFile;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.i18n.I18n;
import io.softa.framework.base.placeholder.PlaceholderKind;
import io.softa.framework.base.placeholder.PlaceholderToken;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.base.utils.ListUtils;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.SubQueries;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.FileService;
import io.softa.framework.orm.utils.FileUtils;
import io.softa.starter.file.dto.ImportDataDTO;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.entity.ImportHistory;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.entity.ImportTemplateField;
import io.softa.starter.file.enums.ImportStatus;
import io.softa.starter.file.enums.ImportType;
import io.softa.starter.file.excel.export.ExcelSheetData;
import io.softa.starter.file.excel.export.support.ExcelUploadService;
import io.softa.starter.file.excel.export.support.OptionDropdownHandler;
import io.softa.starter.file.excel.style.TemporalColumnFormatHandler;
import io.softa.starter.file.excel.export.support.OptionDropdownResolver;
import io.softa.starter.file.excel.imports.ImportHeaderMatcher;
import io.softa.starter.file.excel.imports.ImportRowPipeline;
import io.softa.starter.file.excel.style.CustomHeadStyleHandler;
import io.softa.starter.file.message.AsyncImportProducer;
import io.softa.starter.file.service.ImportHistoryService;
import io.softa.starter.file.service.ImportService;
import io.softa.starter.file.service.ImportTemplateFieldService;
import io.softa.starter.file.service.ImportTemplateService;
import io.softa.starter.file.vo.ImportWizard;

@Slf4j
@Service
public class ImportServiceImpl implements ImportService {
    /** The model that HOLDS an import's files — the history row, not the model being imported. */
    private static final String HISTORY_MODEL = ImportHistory.class.getSimpleName();

    @Autowired
    private FileService fileService;

    @Autowired
    private ImportTemplateService importTemplateService;

    @Autowired
    private ImportTemplateFieldService importTemplateFieldService;

    @Autowired
    private ExcelUploadService excelUploadService;

    @Autowired
    private ImportHistoryService importHistoryService;

    @Autowired
    private ImportRowPipeline importRowPipeline;

    @Autowired
    private AsyncImportProducer asyncImportProducer;

    @Autowired
    private OptionDropdownResolver optionDropdownResolver;

    public void validateImportTemplate(ImportTemplate importTemplate) {
        Assert.notBlank(importTemplate.getModelName(),
                "Import template `{0}` modelName cannot be empty.", importTemplate.getName());
        Assert.notNull(importTemplate.getImportRule(),
                "Import template `{0}` importRule cannot be null.", importTemplate.getName());
        Assert.notEmpty(importTemplate.getImportFields(),
                "Import template `{0}` fields cannot be empty.", importTemplate.getName());
    }

    /**
     * Get the fileInfo of the import template by template ID
     *
     * @param templateId template ID
     * @return import template fileInfo
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public FileInfo getTemplateFile(Long templateId) {
        SubQueries subQueries = new SubQueries().expand(ImportTemplate::getImportFields);
        ImportTemplate importTemplate = loadTemplate(templateId, subQueries)
                .orElseThrow(() -> new IllegalArgumentException("Import template not found by ID: {0}", templateId));
        validateImportTemplate(importTemplate);
        // Construct the importTemplateDTO object
        ImportTemplateDTO importTemplateDTO = this.getImportTemplateDTO(importTemplate, null);

        List<ImportFieldDTO> importFields = importTemplateDTO.getImportFields();
        List<String> headers = importFields.stream().map(ImportFieldDTO::getHeader).toList();
        List<String> requiredHeaderList = importFields.stream()
                .filter(ImportFieldDTO::getRequired).map(ImportFieldDTO::getHeader).toList();

        // 1) Main sheet: header row, plus a dropdown on every column whose values are a fixed list.
        // Resolved here rather than inside the handler so the query happens before the workbook is
        // serialised — a write handler runs during serialisation and should not be holding a
        // connection. An empty map simply means no column qualified.
        CustomHeadStyleHandler headStyleHandler = new CustomHeadStyleHandler(requiredHeaderList);
        // The template's own country, not the request's: an administrator of a company in one country
        // may download another country's template, and inferring it from the request would fill that
        // template with the wrong country's values.
        OptionDropdownResolver.Resolution dropdowns = optionDropdownResolver.resolveAll(
                importTemplate.getModelName(), importFields, importTemplate.getCountry());
        ExcelSheetData mainSheetData = new ExcelSheetData(importTemplate.getName(), headers, Collections.emptyList(),
                new WriteHandler[]{
                        headStyleHandler,
                        new OptionDropdownHandler(dropdowns.optionsByColumn(), dropdowns.cascadesByColumn()),
                        // Without this a date column is General, and the format a typed date takes is
                        // the reader's locale — contradicting the instruction sheet beside it.
                        TemporalColumnFormatHandler.forFields(importTemplate.getModelName(),
                                importFields.stream().map(ImportFieldDTO::getFieldName).toList())
                });
        List<ExcelSheetData> sheetDataList = new ArrayList<>();
        sheetDataList.add(mainSheetData);
        if (Boolean.TRUE.equals(importTemplate.getIncludeDescription())) {
            // 2) Instruction sheet: header row + a second row containing field descriptions.
            // Use the description configured on the import template field (not MetaField.description).
            List<Object> instructionRow = importFields.stream().<Object>map(f -> {
                String description = f.getDescription();
                return description == null ? "" : description;
            }).toList();
            ExcelSheetData instructionSheetData = new ExcelSheetData(
                    "Import instructions",
                    headers,
                    List.of(instructionRow),
                    new WriteHandler[]{
                            headStyleHandler,
                            createInstructionWrapHandler(),
                            createInstructionRowHeightHandler()
                    }
            );
            sheetDataList.add(instructionSheetData);
        }

        return excelUploadService.generateFileAndUpload(
                importTemplate.getModelName(),
                importTemplate.getName(),
                sheetDataList
        );
    }

    /**
     * Import data from the uploaded file and the import template ID
     *
     * @param templateId the ID of the import template
     * @param file the uploaded file
     * @param env the environment variables
     * @return the import result
     */
    @Override
    public ImportHistory importByTemplate(Long templateId, MultipartFile file, Map<String, Object> env) {
        SubQueries subQueries = new SubQueries().expand(ImportTemplate::getImportFields);
        ImportTemplate importTemplate = loadTemplate(templateId, subQueries)
                .orElseThrow(() -> new IllegalArgumentException("Import template not found by ID: {0}", templateId));
        this.validateImportTemplate(importTemplate);
        // The model this file is stamped with decides who may later claim it, and the row that will
        // hold it is the ImportHistory record below — never a row of the model being imported. Stamped
        // with the business model, the write of ImportHistory.originalFileId is refused with
        // "File … is not yours to attach", which fails the whole import.
        Long fileId = fileService.uploadFile(HISTORY_MODEL, file);
        // Generate an import history record
        ImportHistory importHistory = this.generateImportHistory(importTemplate, fileId, ImportType.IMPORT);
        // generate the ImportDataDTO object and ImportTemplateDTO object
        String fileName = FileUtils.getShortFileName(file);
        ImportTemplateDTO importTemplateDTO = this.getImportTemplateDTO(importTemplate, env);
        importTemplateDTO.setFileId(fileId);
        importTemplateDTO.setHistoryId(importHistory.getId());
        importTemplateDTO.setFileName(fileName);
        if (Boolean.TRUE.equals(importTemplate.getSyncImport())) {
            try (InputStream inputStream = file.getInputStream()) {
                return this.syncImport(importTemplateDTO, inputStream, importHistory);
            } catch (IOException e) {
                throw new BusinessException("Failed to read uploaded Excel file {0}", fileName, e);
            }
        } else {
            asyncImportProducer.sendAsyncImport(importTemplateDTO);
        }
        return importHistory;
    }

    private WriteHandler createInstructionWrapHandler() {
        return new CellWriteHandler() {
            @Override
            public void afterCellDispose(CellWriteHandlerContext context) {
                if (context.getHead()) {
                    return;
                }
                WriteCellStyle writeCellStyle = context.getFirstCellData().getOrCreateStyle();
                writeCellStyle.setWrapped(Boolean.TRUE);
                writeCellStyle.setVerticalAlignment(VerticalAlignment.TOP);
            }
        };
    }

    private WriteHandler createInstructionRowHeightHandler() {
        return new RowWriteHandler() {
            @Override
            public void afterRowDispose(RowWriteHandlerContext context) {
                Row row = context.getRow();
                if (context.getHead() || row == null) {
                    return;
                }
                row.setHeightInPoints(calculateInstructionRowHeight(row));
            }
        };
    }

    private float calculateInstructionRowHeight(Row row) {
        int estimatedLineCount = 1;
        short lastCellNum = row.getLastCellNum();
        if (lastCellNum <= 0) {
            return estimatedLineCount * 16f;
        }
        int charsPerLine = Math.max(1, io.softa.starter.file.constant.FileConstant.DEFAULT_EXCEL_COLUMN_WIDTH - 4);
        for (int i = 0; i < lastCellNum; i++) {
            Cell cell = row.getCell(i);
            if (cell == null) {
                continue;
            }
            String cellText = cell.toString();
            if (StringUtils.isBlank(cellText)) {
                continue;
            }
            estimatedLineCount = Math.max(estimatedLineCount, estimateWrappedLineCount(cellText, charsPerLine));
        }
        return estimatedLineCount * 16f;
    }

    private int estimateWrappedLineCount(String text, int charsPerLine) {
        int lineCount = 0;
        for (String segment : text.split("\\R", -1)) {
            int segmentLength = Math.max(1, segment.length());
            lineCount += Math.max(1, (segmentLength + charsPerLine - 1) / charsPerLine);
        }
        return Math.max(1, lineCount);
    }

    /**
     * Validate data from the uploaded file using the import template ID (no persistence).
     *
     * @param templateId the ID of the import template
     * @param file       the uploaded file
     * @param env        the environment variables
     * @return the validation result as ImportHistory
     */
    @Override
    public ImportHistory validateByTemplate(Long templateId, MultipartFile file, Map<String, Object> env) {
        SubQueries subQueries = new SubQueries().expand(ImportTemplate::getImportFields);
        ImportTemplate importTemplate = loadTemplate(templateId, subQueries)
                .orElseThrow(() -> new IllegalArgumentException("Import template not found by ID: {0}", templateId));
        this.validateImportTemplate(importTemplate);
        Long fileId = fileService.uploadFile(HISTORY_MODEL, file);
        // Generate a validation history record
        ImportHistory importHistory = this.generateImportHistory(importTemplate, fileId, ImportType.VALIDATE);
        String fileName = FileUtils.getShortFileName(file);
        ImportTemplateDTO importTemplateDTO = this.getImportTemplateDTO(importTemplate, env);
        importTemplateDTO.setFileId(fileId);
        importTemplateDTO.setHistoryId(importHistory.getId());
        importTemplateDTO.setFileName(fileName);
        try (InputStream inputStream = file.getInputStream()) {
            return this.syncValidate(importTemplateDTO, inputStream, importHistory);
        } catch (IOException e) {
            throw new BusinessException("Failed to read uploaded Excel file {0}", fileName, e);
        }
    }

    /**
     * Synchronous import data from the uploaded file and import template
     *
     * @param importTemplateDTO the import template DTO
     * @param inputStream the input stream of the uploaded file
     * @param importHistory the import history object
     * @return the import history object
     */
    public ImportHistory syncImport(ImportTemplateDTO importTemplateDTO, InputStream inputStream, ImportHistory importHistory) {
        long startNanos = System.nanoTime();
        RuntimeException importException = null;
        try {
            ImportDataDTO importDataDTO = this.generateImportDataDTO(importTemplateDTO, inputStream);
            importRowPipeline.importData(importTemplateDTO, importDataDTO);
            updateImportStatistics(importHistory, importDataDTO);
            if (!CollectionUtils.isEmpty(importDataDTO.getFailedRows())) {
                Long failedFileId = this.generateFailedExcel(importTemplateDTO.getFileName(), importTemplateDTO, importDataDTO);
                importHistory.setFailedFileId(failedFileId);
                ImportStatus status = CollectionUtils.isEmpty(importDataDTO.getRows()) ?
                        ImportStatus.FAILURE : ImportStatus.PARTIAL_FAILURE;
                importHistory.setStatus(status);
            } else {
                importHistory.setStatus(ImportStatus.SUCCESS);
            }
            return importHistory;
        } catch (RuntimeException e) {
            importHistory.setStatus(ImportStatus.FAILURE);
            importHistory.setErrorMessage(e.getMessage());
            importException = e;
            throw e;
        } finally {
            importHistory.setDuration(DateUtils.elapsedSeconds(startNanos));
            this.updateImportHistory(importHistory, importException);
        }
    }

    private void updateImportStatistics(ImportHistory importHistory, ImportDataDTO importDataDTO) {
        int failedCount = importDataDTO.getFailedRows() != null ? importDataDTO.getFailedRows().size() : 0;
        int successCount = importDataDTO.getRows() != null ? importDataDTO.getRows().size() : 0;
        importHistory.setTotalRows(successCount + failedCount);
        importHistory.setFailedRows(failedCount);
        importHistory.setSuccessRows(successCount);
    }

    /**
     * Synchronous validation of data from the uploaded file (no persistence).
     * Runs the validation pipeline (which forces skipException=true) and generates a result Excel
     * containing ALL rows (both passed and failed) with a FAILED_REASON column for complete user feedback.
     *
     * @param importTemplateDTO the import template DTO
     * @param inputStream the input stream of the uploaded file
     * @param importHistory the import history object
     * @return the import history object with validation status
     */
    public ImportHistory syncValidate(ImportTemplateDTO importTemplateDTO, InputStream inputStream, ImportHistory importHistory) {
        long startNanos = System.nanoTime();
        RuntimeException validateException = null;
        try {
            ImportDataDTO importDataDTO = this.generateImportDataDTO(importTemplateDTO, inputStream);
            importRowPipeline.validateData(importTemplateDTO, importDataDTO);
            int failedCount = importDataDTO.getFailedRows() != null ? importDataDTO.getFailedRows().size() : 0;
            int successCount = importDataDTO.getRows().size();
            importHistory.setTotalRows(successCount + failedCount);
            importHistory.setFailedRows(failedCount);
            importHistory.setSuccessRows(successCount);
            // Generate validation result Excel containing ALL rows (passed + failed)
            Long resultFileId = this.generateValidationResultExcel(
                    importTemplateDTO.getFileName(), importTemplateDTO, importDataDTO);
            importHistory.setFailedFileId(resultFileId);
            if (failedCount > 0) {
                importHistory.setStatus(ImportStatus.VALIDATION_FAILURE);
            } else {
                importHistory.setStatus(ImportStatus.VALIDATION_SUCCESS);
            }
            return importHistory;
        } catch (RuntimeException e) {
            importHistory.setStatus(ImportStatus.VALIDATION_FAILURE);
            importHistory.setErrorMessage(e.getMessage());
            validateException = e;
            throw e;
        } finally {
            importHistory.setDuration(DateUtils.elapsedSeconds(startNanos));
            this.updateImportHistory(importHistory, validateException);
        }
    }

    private void updateImportHistory(ImportHistory importHistory, RuntimeException importException) {
        try {
            importHistoryService.updateOne(importHistory);
        } catch (RuntimeException updateException) {
            if (importException != null) {
                log.error("Failed to update import history `{}` after import exception.", importHistory.getId(), updateException);
                return;
            }
            throw updateException;
        }
    }

    /**
     * Import data from the uploaded file and dynamic import settings
     *
     * @return the import result
     */
    @Override
    public ImportHistory importByDynamic(ImportWizard importWizard) {
        String fileName = importWizard.getFileName();
        Long fileId = fileService.uploadFile(HISTORY_MODEL, importWizard.getFile());
        // Generate an import history record
        ImportHistory importHistory = this.generateImportHistory(importWizard, fileId, ImportType.IMPORT);
        // generate the ImportDataDTO object and ImportTemplateDTO object
        ImportTemplateDTO importTemplateDTO = this.convertToImportTemplateDTO(importWizard);
        importTemplateDTO.setFileId(fileId);
        importTemplateDTO.setHistoryId(importHistory.getId());
        importTemplateDTO.setFileName(fileName);
        if (Boolean.TRUE.equals(importWizard.getSyncImport())) {
            try (InputStream inputStream = importWizard.getFile().getInputStream()) {
                return this.syncImport(importTemplateDTO, inputStream, importHistory);
            } catch (IOException e) {
                throw new BusinessException("Failed to read uploaded Excel file {0}", fileName, e);
            }
        } else {
            asyncImportProducer.sendAsyncImport(importTemplateDTO);
        }
        return importHistory;
    }

    /**
     * Validate data from the uploaded file using dynamic import settings (no persistence).
     *
     * @param importWizard the import wizard with dynamic settings
     * @return the validation result as ImportHistory
     */
    @Override
    public ImportHistory validateByDynamic(ImportWizard importWizard) {
        String fileName = importWizard.getFileName();
        Long fileId = fileService.uploadFile(HISTORY_MODEL, importWizard.getFile());
        // Generate a validation history record
        ImportHistory importHistory = this.generateImportHistory(importWizard, fileId, ImportType.VALIDATE);
        ImportTemplateDTO importTemplateDTO = this.convertToImportTemplateDTO(importWizard);
        importTemplateDTO.setFileId(fileId);
        importTemplateDTO.setHistoryId(importHistory.getId());
        importTemplateDTO.setFileName(fileName);
        try (InputStream inputStream = importWizard.getFile().getInputStream()) {
            return this.syncValidate(importTemplateDTO, inputStream, importHistory);
        } catch (IOException e) {
            throw new BusinessException("Failed to read uploaded Excel file {0}", fileName, e);
        }
    }

    /**
     * Generate the ImportDataDTO object to store the data during the import process.
     *
     * @param importTemplateDTO the importTemplateDTO object
     * @param inputStream the input stream of the uploaded file
     * @return the generated ImportDataDTO object
     */
    private ImportDataDTO generateImportDataDTO(ImportTemplateDTO importTemplateDTO, InputStream inputStream) {
        ImportHeaderMatcher headerMatcher = ImportHeaderMatcher.of(importTemplateDTO.getImportFields());
        List<Map<String, Object>> allDataList =
                this.extractDataFromExcel(headerMatcher, importTemplateDTO.getFileName(), inputStream);
        Assert.notEmpty(allDataList, "No data exists in the excel file `{0}`", importTemplateDTO.getFileName());
        ImportDataDTO importDataDTO = new ImportDataDTO();
        importDataDTO.setRows(allDataList);
        importDataDTO.setEnv(importTemplateDTO.getEnv());
        importDataDTO.setOriginalRows(ListUtils.deepCopy(allDataList));
        return importDataDTO;
    }

    /**
     * The template with its fields, read past the caller's row scope.
     *
     * <p>Reaching this service means the endpoint gate already admitted the caller to import with
     * templates; which template, and the columns it is made of, are not the caller's data to be
     * row-scoped. Read under the caller's scope, the field rows — a child no role grants and the
     * template's own declared scope does not reach — come back empty, and the import fails on a
     * template with no columns.
     */
    private Optional<ImportTemplate> loadTemplate(Long templateId, SubQueries subQueries) {
        return pastRowScope(() -> importTemplateService.getById(templateId, subQueries));
    }

    /** The template's columns in sequence, read past the caller's row scope for the reason above. */
    private List<ImportTemplateField> loadTemplateFields(Long templateId) {
        Filters filters = new Filters().eq(ImportTemplateField::getTemplateId, templateId);
        Orders orders = Orders.ofAsc(ImportTemplateField::getSequence);
        return pastRowScope(() -> importTemplateFieldService.searchList(new FlexQuery(filters, orders)));
    }

    private static <T> T pastRowScope(ScopedValue.CallableOp<T, RuntimeException> read) {
        Context ctx = ContextHolder.cloneContext();
        ctx.setSkipPermissionCheck(true);
        return ContextHolder.callWith(ctx, read);
    }

    /**
     * Get the ImportTemplateDTO object by importTemplate
     *
     * @param importTemplate the import template object
     * @return importTemplateDTO object
     */
    public ImportTemplateDTO getImportTemplateDTO(ImportTemplate importTemplate, Map<String, Object> env) {
        ImportTemplateDTO importTemplateDTO = this.convertToImportTemplateDTO(importTemplate, env);
        // Construct the headers order by sequence of the export fields
        List<ImportTemplateField> importTemplateFields = loadTemplateFields(importTemplate.getId());
        importTemplateFields.forEach(importTemplateField -> {
            ImportFieldDTO importFieldDTO = convertToImportFieldDTO(importTemplateDTO, importTemplateField);
            importTemplateDTO.addImportField(importFieldDTO);
        });
        return importTemplateDTO;
    }

    /**
     * Convert the importTemplate to the importTemplateDTO.
     *
     * @param importTemplate the importTemplate object
     * @param env the environment variables
     * @return the converted importTemplateDTO
     */
    private ImportTemplateDTO convertToImportTemplateDTO(ImportTemplate importTemplate, Map<String, Object> env) {
        ImportTemplateDTO importTemplateDTO = new ImportTemplateDTO();
        importTemplateDTO.setTemplateId(importTemplate.getId());
        importTemplateDTO.setModelName(importTemplate.getModelName());
        importTemplateDTO.setImportRule(importTemplate.getImportRule());
        importTemplateDTO.setIgnoreEmpty(importTemplate.getIgnoreEmpty());
        importTemplateDTO.setSkipException(importTemplate.getSkipException());
        importTemplateDTO.setCustomHandler(importTemplate.getCustomHandler());
        importTemplateDTO.setUniqueConstraints(importTemplate.getUniqueConstraints());
        importTemplateDTO.setEnv(env);
        return importTemplateDTO;
    }

    /**
     * Convert the importWizard to the importTemplateDTO.
     *
     * @param importWizard the importWizard object
     * @return the converted importTemplateDTO
     */
    private ImportTemplateDTO convertToImportTemplateDTO(ImportWizard importWizard) {
        ImportTemplateDTO importTemplateDTO = new ImportTemplateDTO();
        importTemplateDTO.setModelName(importWizard.getModelName());
        importTemplateDTO.setImportRule(importWizard.getImportRule());
        importTemplateDTO.setIgnoreEmpty(importWizard.getIgnoreEmpty());
        importTemplateDTO.setSkipException(importWizard.getSkipException());
        importTemplateDTO.setCustomHandler(importWizard.getCustomHandler());
        List<String> uniqueConstraints = StringUtils.isNotBlank(importWizard.getUniqueConstraints())
                ? List.of(importWizard.getUniqueConstraints().split(","))
                : Collections.emptyList();
        importTemplateDTO.setUniqueConstraints(uniqueConstraints);
        // set the import fields
        importTemplateDTO.setImportFields(importWizard.getImportFieldDTOList());
        // update the `ignoreEmpty` of the import fields
        importTemplateDTO.getImportFields()
                .forEach(importFieldDTO -> importFieldDTO.setIgnoreEmpty(importTemplateDTO.getIgnoreEmpty()));
        return importTemplateDTO;
    }

    /**
     * Convert the import template field to the import field DTO.
     *
     * @param importTemplateDTO      the importTemplateDTO object
     * @param importTemplateField the import template field
     * @return the converted import field DTO
     */
    private ImportFieldDTO convertToImportFieldDTO(ImportTemplateDTO importTemplateDTO, ImportTemplateField importTemplateField) {
        ImportFieldDTO importFieldDTO = new ImportFieldDTO();
        importFieldDTO.setFieldName(importTemplateField.getFieldName());
        importFieldDTO.setRequired(importTemplateField.getRequired());
        importFieldDTO.setIgnoreEmpty(importTemplateDTO.getIgnoreEmpty());
        importFieldDTO.setDescription(importTemplateField.getDescription());
        importFieldDTO.setNoDropdown(importTemplateField.getNoDropdown());
        // Get the metaField object of the last field in cascading `fieldName`.
        MetaField lastField = resolveLastImportField(importTemplateDTO.getModelName(), importTemplateField.getFieldName());
        // Set the default value of the imported field
        if (StringUtils.isNotBlank(importTemplateField.getDefaultValue())) {
            Object defaultValue = this.getDefaultValue(lastField.getFieldType(), importTemplateField.getDefaultValue(), importTemplateDTO.getEnv());
            importFieldDTO.setDefaultValue(defaultValue);
        }
        // If the custom header is not set, use the label of the field as the header
        if (StringUtils.isNotBlank(importTemplateField.getCustomHeader())) {
            importFieldDTO.setHeader(importTemplateField.getCustomHeader());
        } else {
            importFieldDTO.setHeader(lastField.getLabel());
        }
        return importFieldDTO;
    }

    /**
     * Resolve the last field for import template field names.
     *
     * <p>Unlike generic cascaded-field resolution, import lookup supports to-many relation roots
     * such as {@code roleIds.code} and {@code additionalProjectTeamIds.code}.
     */
    private MetaField resolveLastImportField(String modelName, String fullFieldName) {
        String[] fieldsArray = StringUtils.split(fullFieldName, ".");
        Assert.isTrue(fieldsArray != null && fieldsArray.length > 0,
                "Import field `{0}` cannot be empty.", fullFieldName);

        MetaField metaField = null;
        for (int i = 0; i < fieldsArray.length; i++) {
            metaField = ModelManager.getModelField(modelName, fieldsArray[i]);
            if (i < fieldsArray.length - 1) {
                Assert.isTrue(FieldType.RELATED_TYPES.contains(metaField.getFieldType()),
                        "The field {0} in import lookup field {1} must be relation field!",
                        metaField.getFieldName(), fullFieldName);
                Assert.notBlank(metaField.getRelatedModel(),
                        "The field {0} in import lookup field {1} has no related model configured!",
                        metaField.getFieldName(), fullFieldName);
                modelName = metaField.getRelatedModel();
            } else {
                Assert.notTrue(metaField.isDynamic(),
                        "The last field {0} in import field {1} must be a stored field in model {2}!",
                        metaField.getFieldName(), fullFieldName, metaField.getModelName());
            }
        }
        return metaField;
    }

    /**
     * Get the default value of the field.
     *
     * @param fieldType the field type
     * @param defaultValue the default value
     * @param env the environment variables
     * @return the default value
     */
    private Object getDefaultValue(FieldType fieldType, String defaultValue, Map<String, Object> env) {
        PlaceholderToken placeholder = PlaceholderUtils.parsePlaceholder(defaultValue);
        if (placeholder == null) {
            return FieldType.convertStringToFieldValue(fieldType, defaultValue);
        } else if (PlaceholderKind.VARIABLE.equals(placeholder.getKind())) {
            return PlaceholderUtils.extractVariable(placeholder, env);
        } else {
            throw new IllegalArgumentException("The default value `{0}` is not a valid placeholder or literal value.", defaultValue);
        }
    }

    /**
     * Extract the data from the uploaded Excel file.
     * Load the first sheet of the Excel file by FesodSheet.read(file, {}).sheet(0).doRead().
     *
     * @param headerMatcher pairs the sheet's header row with the template's declared columns
     * @param fileName the uploaded file's name, for the mismatch message
     * @param inputStream the input stream of the uploaded file
     */
    // Package-private, not private: the header check only proves itself against a real workbook read
    // by the real reader, and that read is what a test has to be able to call.
    List<Map<String, Object>> extractDataFromExcel(ImportHeaderMatcher headerMatcher, String fileName,
                                                   InputStream inputStream) {
        List<Map<String, Object>> rows = new ArrayList<>();
        FesodSheet.read(inputStream, new AnalysisEventListener<Map<Integer, String>>() {
            // The mapping of column index and header name
            private Map<Integer, String> headerMap = new HashMap<>();

            @Override
            public void invoke(Map<Integer, String> rowData, AnalysisContext context) {
                // Create a row data Map
                Map<String, Object> mappedRow = new HashMap<>();
                for (Map.Entry<Integer, String> entry : rowData.entrySet()) {
                    String headerName = headerMap.get(entry.getKey());
                    String fieldName = headerMatcher.fieldNameFor(headerName);
                    if (fieldName != null) {
                        mappedRow.put(fieldName, entry.getValue());
                    }
                }
                rows.add(mappedRow);
            }

            // Save the header mapping, and settle here whether this file was written from the
            // selected template at all. Reading on regardless is what turned "wrong template" into
            // a required-field error on every row, naming fields the file's object does not have.
            @Override
            public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
                this.headerMap = headMap;
                headerMatcher.assertMatches(headMap.values(), fileName);
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                log.info("All data processed!");
            }
        }).sheet(0).doRead();
        return rows;
    }

    /**
     * Generate an import history record.
     *
     * @param template the import template object
     * @param fileId the fileId of the exported file in FileRecord model
     * @param importType the import type (IMPORT or VALIDATE)
     * @return the generated importHistory object
     */
    protected ImportHistory generateImportHistory(ImportTemplate template, Long fileId,
                                                  ImportType importType) {
        ImportHistory importHistory = new ImportHistory();
        importHistory.setTemplateId(template.getId());
        importHistory.setModelName(template.getModelName());
        importHistory.setOriginalFileId(fileId);
        importHistory.setImportType(importType);
        importHistory.setImportRule(template.getImportRule());
        importHistory.setStatus(ImportStatus.PROCESSING);
        Long id = importHistoryService.createOne(importHistory);
        importHistory.setId(id);
        return importHistory;
    }

    protected ImportHistory generateImportHistory(ImportWizard importWizard, Long fileId,
                                                  ImportType importType) {
        ImportHistory importHistory = new ImportHistory();
        importHistory.setModelName(importWizard.getModelName());
        importHistory.setOriginalFileId(fileId);
        importHistory.setImportType(importType);
        importHistory.setImportRule(importWizard.getImportRule());
        importHistory.setStatus(ImportStatus.PROCESSING);
        Long id = importHistoryService.createOne(importHistory);
        importHistory.setId(id);
        return importHistory;
    }

    /**
     * Generate an Excel file consist of failed data.
     *
     * @param fileName the name of the file with failed data
     * @param importTemplateDTO the import template DTO
     * @param importDataDTO the import data DTO
     * @return the fileId of the generated Excel file with failed data
     */
    private Long generateFailedExcel(String fileName, ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        fileName = fileName + "_" + FileConstant.FAILED_DATA;
        List<String> headers = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        importTemplateDTO.getImportFields().forEach(importFieldDTO -> {
            fields.add(importFieldDTO.getFieldName());
            headers.add(importFieldDTO.getHeader());
        });
        // Add the failed reason header and column
        fields.add(FileConstant.FAILED_REASON);
        headers.add(I18n.get(FileConstant.FAILED_REASON));
        // Get the data to be exported
        List<List<Object>> rowsTable = ListUtils.convertToTableData(fields, importDataDTO.getFailedRows());
        ExcelSheetData sheetData = new ExcelSheetData(FileConstant.FAILED_DATA, headers, rowsTable, null);
        // Stamped with the holder, not the subject — see importByTemplate. This one lands on
        // ImportHistory.failedFileId.
        FileInfo fileInfo = excelUploadService.generateFileAndUpload(HISTORY_MODEL, fileName, sheetData);
        return fileInfo.getFileId();
    }

    /**
     * Generate a validation result Excel file containing ALL rows (both passed and failed).
     * Each row includes a FAILED_REASON column: empty for passed rows, error messages for failed rows.
     * This provides complete feedback so the user can see which rows will pass and which will fail.
     *
     * @param fileName the base name of the file
     * @param importTemplateDTO the import template DTO
     * @param importDataDTO the import data DTO (with rows and failedRows already separated by ImportFailureCollector)
     * @return the fileId of the generated validation result Excel file
     */
    private Long generateValidationResultExcel(String fileName, ImportTemplateDTO importTemplateDTO, ImportDataDTO importDataDTO) {
        fileName = fileName + "_Validation Result";
        List<String> headers = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        importTemplateDTO.getImportFields().forEach(importFieldDTO -> {
            fields.add(importFieldDTO.getFieldName());
            headers.add(importFieldDTO.getHeader());
        });
        // Add the failed reason header and column
        fields.add(FileConstant.FAILED_REASON);
        headers.add(I18n.get(FileConstant.FAILED_REASON));
        // Merge all rows: failed original rows first, then passed original rows (FAILED_REASON is empty for passed rows)
        List<Map<String, Object>> allRows = new ArrayList<>();
        if (!CollectionUtils.isEmpty(importDataDTO.getFailedRows())) {
            allRows.addAll(importDataDTO.getFailedRows());
        }
        if (!CollectionUtils.isEmpty(importDataDTO.getOriginalRows())) {
            // originalRows still holds the passed rows (failures were removed by ImportFailureCollector)
            allRows.addAll(importDataDTO.getOriginalRows());
        }
        List<List<Object>> rowsTable = ListUtils.convertToTableData(fields, allRows);
        ExcelSheetData sheetData = new ExcelSheetData("Validation Result", headers, rowsTable, null);
        // Stamped with the holder, not the subject — see importByTemplate. This one lands on
        // ImportHistory.failedFileId.
        FileInfo fileInfo = excelUploadService.generateFileAndUpload(HISTORY_MODEL, fileName, sheetData);
        return fileInfo.getFileId();
    }

}
