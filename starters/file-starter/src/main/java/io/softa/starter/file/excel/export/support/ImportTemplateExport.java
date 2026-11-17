package io.softa.starter.file.excel.export.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.base.utils.ListUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.FieldPathUse;
import io.softa.framework.orm.meta.FieldPaths;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.file.dto.ExportResult;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.entity.ExportHistory;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.excel.export.ExcelSheetData;
import io.softa.starter.file.excel.imports.ImportTemplateWorkbook;
import io.softa.starter.file.service.ImportService;
import io.softa.starter.file.service.ImportTemplateService;
import io.softa.starter.file.support.ImportTemplateCountryScope;
import io.softa.starter.file.support.ImportTemplateDisplayOrder;
import io.softa.starter.file.vo.ExportableImportTemplate;

/**
 * Export records into an import template's workbook, to be edited and imported back through that same
 * template.
 *
 * <p>An import template is the one statement of what an importable sheet of its model looks like: the
 * columns, the key each relation is named by ({@code departmentId.code}, {@code jobLevelId.name}), the
 * unique key a row is matched back to its record by, the country, the dropdowns. A dynamic export does
 * not know any of that and writes relations by their display names, so its file does not go back in.
 * Written here with the template's own columns and workbook ({@link ImportTemplateWorkbook}), the file
 * needs no mapping on the way back.
 *
 * <p>Each column is read so that what lands in the cell is what that column of the import takes:
 * <ul>
 *   <li>a dotted column ({@code departmentId.code}, {@code employeeProfileId.passType.name}) by its own
 *       path — the key it names the relation by;</li>
 *   <li>a bare to-one column ({@code currency}) by the related row's id: the import takes the id there,
 *       which for a code-as-id master is its code ({@code SGD}), not the name a display read gives;</li>
 *   <li>every other column as an export reads it, which writes an option by its label and a boolean as
 *       Yes / No — what the import takes, and what the template's dropdowns list.</li>
 * </ul>
 *
 * <p>Offered for a template a row can be matched back through: one that updates, of a model without
 * effective-dated versions, with a column for every field of its unique key, and with no column that
 * names several records. A required column the caller cannot see makes the template unusable for them;
 * an optional one is left out of the file, which the import then simply does not write. Columns the
 * caller may not write are already gone from the template as the caller gets it
 * ({@link ImportService#getImportTemplateDTO}).
 *
 * <p>Asked of a caller who holds both the export and the import of the model: a file for re-import is
 * for someone who can import it back.
 */
@Slf4j
@Component
public class ImportTemplateExport {

    /** The model that HOLDS an exported file — the history row, not the model exported. */
    private static final String HISTORY_MODEL = ExportHistory.class.getSimpleName();

    @Autowired
    private ImportTemplateService importTemplateService;

    @Autowired
    private ImportService importService;

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private ExportDataFetcher exportDataFetcher;

    @Autowired
    private ImportTemplateWorkbook importTemplateWorkbook;

    @Autowired
    private ExcelUploadService excelUploadService;

    /**
     * The import templates of {@code modelName} a file for re-import can be exported with by the caller,
     * in display order. Only the model's own: a child model's template would need the rows of the page's
     * model turned into its own. Empty when the caller cannot both export and import the model.
     */
    public List<ExportableImportTemplate> listFor(String modelName) {
        Assert.notBlank(modelName, "The model to export must be specified.");
        if (!ModelManager.existModel(modelName)
                || !permissionService.hasModelImportGrant(modelName)
                || !permissionService.hasModelExportGrant(modelName)) {
            return List.of();
        }
        // The same listing the import dialog shows — the caller's countries plus the templates for every
        // country — narrowed to the model itself.
        Filters filters = ImportTemplateCountryScope.withCountryScope(
                new Filters().eq(ImportTemplate::getModelName, modelName));
        List<ImportTemplate> templates = ImportTemplateDisplayOrder.inDisplayOrder(
                importTemplateService.searchList(new FlexQuery(filters)));
        Map<String, Set<String>> hidden = permissionService.getHiddenSensitiveFields();
        List<ExportableImportTemplate> exportable = new ArrayList<>();
        for (ImportTemplate template : templates) {
            Sheet sheet = Sheet.of(template, importService.getImportTemplateDTO(template, null), hidden);
            if (sheet.refusal() == null) {
                exportable.add(sheet.describe(template));
            } else {
                log.debug("Import template {} is not offered for re-import: {}", template.getId(),
                        sheet.refusal().pattern());
            }
        }
        return exportable;
    }

    /**
     * Write the rows {@code flexQuery} selects into the import template's workbook.
     *
     * @param modelName        the model of the list the export was asked from; the template must be its own
     * @param importTemplateId the import template
     * @param flexQuery        the rows: filters, orders, effective date; its fields are the template's
     * @return the uploaded file and how many rows it holds
     */
    public ExportResult export(String modelName, Long importTemplateId, FlexQuery flexQuery) {
        Assert.notBlank(modelName, "The model to export must be specified.");
        Assert.notNull(importTemplateId, "The import template must be specified.");
        if (!permissionService.hasModelImportGrant(modelName)) {
            throw new PermissionException(
                    "You are not permitted to import {0}, which a file for re-import is exported for.", modelName);
        }
        exportDataFetcher.assertMayExport(modelName);
        // Configuration, not the caller's data: read past their row scope, as an import reads it.
        ImportTemplate template = ContextUtils.withoutPermissionChecks(() -> importTemplateService.getById(importTemplateId))
                .orElseThrow(() -> new IllegalArgumentException("Import template not found by ID: {0}", importTemplateId));
        if (!modelName.equals(template.getModelName())) {
            throw new IllegalArgumentException("Import template {0} is a template of {1}, not of {2}.",
                    template.getName(), template.getModelName(), modelName);
        }
        Sheet sheet = Sheet.of(template, importService.getImportTemplateDTO(template, null),
                permissionService.getHiddenSensitiveFields());
        if (sheet.refusal() != null) {
            throw new BusinessException(sheet.refusal().pattern(), sheet.refusal().args());
        }
        // A copy: the query builder writes its own working set back onto the flexQuery's fields.
        List<String> readPaths = List.copyOf(sheet.readPaths());
        flexQuery.setFields(new ArrayList<>(new LinkedHashSet<>(readPaths)));
        flexQuery.setConvertType(ConvertType.DISPLAY);
        Filters byCountry = countryFilter(modelName, template.getCountry());
        if (byCountry != null) {
            flexQuery.setFilters(Filters.isEmpty(flexQuery.getFilters())
                    ? byCountry : Filters.and(flexQuery.getFilters(), byCountry));
        }
        List<Map<String, Object>> rows = exportDataFetcher.fetchRows(modelName, null, flexQuery);
        List<List<Object>> table = ListUtils.convertToTableData(readPaths, rows);
        List<ExcelSheetData> sheets = importTemplateWorkbook.sheets(template, sheet.columns(), table);
        // Stamped with the model that will HOLD it — the ExportHistory row — as every export file is.
        FileInfo fileInfo = excelUploadService.generateFileAndUpload(HISTORY_MODEL, template.getName(), sheets);
        return new ExportResult(fileInfo, table.size());
    }

    /**
     * The path a column is read by, so the cell holds what the column's import takes: a bare to-one
     * column is imported as the related row's id, so it is read as that id; every other column by its
     * own path.
     */
    static String readPath(String modelName, String fieldName) {
        if (fieldName.contains(".") || !ModelManager.existField(modelName, fieldName)) {
            return fieldName;
        }
        MetaField field = ModelManager.getModelField(modelName, fieldName);
        return FieldType.TO_ONE_TYPES.contains(field.getFieldType())
                ? fieldName + "." + ModelConstant.ID : fieldName;
    }

    /**
     * Only the rows of the template's country, judged as the import judges a row's country — by the
     * company the row names ({@code legalEntityId} on an employee, {@code companyId} on a department),
     * and on the company model by its own country. Null when the template is for every country, or when
     * the model names no company: its rows are not tied to one, and the template's country decides only
     * how their values are looked up.
     *
     * <p>Without it a New Zealand employee would be written into the Singapore template, its
     * Singapore-only required columns blank, and fail when imported back.
     */
    static @Nullable Filters countryFilter(String modelName, @Nullable String country) {
        if (StringUtils.isBlank(country)) {
            return null;
        }
        if (ModelConstant.COMPANY_MODEL.equals(modelName)) {
            return ModelManager.existField(modelName, ModelConstant.COUNTRY_FIELD)
                    ? new Filters().eq(ModelConstant.COUNTRY_FIELD, country.trim()) : null;
        }
        for (MetaField field : ModelManager.getModelFields(modelName)) {
            if (!FieldType.MANY_TO_ONE.equals(field.getFieldType())
                    || !ModelConstant.COMPANY_MODEL.equals(field.getRelatedModel())) {
                continue;
            }
            // A company anchor joined at query time names its source path; filter on that.
            String company = field.isDynamic() && StringUtils.isNotBlank(field.getCascadedField())
                    ? field.getCascadedField() : field.getFieldName();
            return new Filters().eq(company + "." + ModelConstant.COUNTRY_FIELD, country.trim());
        }
        return null;
    }

    /** Why a template cannot be exported with, worded for {@link BusinessException}. */
    record Refusal(String pattern, Object... args) {
    }

    /**
     * The file a template gives the caller: its columns, the path each is read by, and the reason it
     * cannot be used when it cannot.
     */
    record Sheet(List<ImportFieldDTO> columns, List<String> readPaths, List<String> uniqueKeys,
                 @Nullable Refusal refusal) {

        static Sheet refused(Refusal refusal) {
            return new Sheet(List.of(), List.of(), List.of(), refusal);
        }

        /**
         * @param template the template, for its name, rule and unique key
         * @param shape    the template as the caller's import reads it — columns the caller may not write
         *                 already left out
         * @param hidden   the sensitive fields the caller can never see, per model
         */
        static Sheet of(ImportTemplate template, ImportTemplateDTO shape, Map<String, Set<String>> hidden) {
            String modelName = template.getModelName();
            String name = template.getName();
            ImportRule rule = template.getImportRule();
            if (rule != ImportRule.CREATE_OR_UPDATE && rule != ImportRule.ONLY_UPDATE) {
                return refused(new Refusal("Import template {0} only creates records, so a file "
                        + "exported with it would create each of them again.", name));
            }
            if (ModelManager.getModel(modelName).isTimeline()) {
                return refused(new Refusal("Import template {0} is for records with effective-dated "
                        + "versions, which a file for re-import does not carry yet.", name));
            }
            List<String> uniqueKeys = template.getUniqueConstraints() == null ? List.of()
                    : template.getUniqueConstraints().stream().filter(StringUtils::isNotBlank).toList();
            if (uniqueKeys.isEmpty()) {
                return refused(new Refusal("Import template {0} names no unique key, so a row of the "
                        + "file could not be matched back to its record.", name));
            }
            List<ImportFieldDTO> columns = new ArrayList<>();
            List<String> readPaths = new ArrayList<>();
            for (ImportFieldDTO column : shape.getImportFields()) {
                String fieldName = column.getFieldName();
                String root = StringUtils.substringBefore(fieldName, ".");
                if (ModelManager.existField(modelName, root)
                        && FieldType.TO_MANY_TYPES.contains(ModelManager.getModelField(modelName, root).getFieldType())) {
                    return refused(new Refusal("Column {0} of import template {1} names several records, "
                            + "which a file for re-import does not write yet.", column.getHeader(), name));
                }
                String readPath = readPath(modelName, fieldName);
                String problem = FieldPaths.problem(modelName, readPath, FieldPathUse.READ);
                if (problem != null) {
                    return refused(new Refusal("Column {0} of import template {1} cannot be exported: {2}",
                            column.getHeader(), name, problem));
                }
                MetaField last = ModelManager.getLastFieldOfCascaded(modelName, readPath);
                if (hidden.getOrDefault(last.getModelName(), Set.of()).contains(last.getFieldName())) {
                    if (Boolean.TRUE.equals(column.getRequired())) {
                        return refused(new Refusal("Import template {0} requires column {1}, "
                                + "which you cannot see.", name, column.getHeader()));
                    }
                    // Left out of the file: the import reads a file without an optional column, and then
                    // does not write that field — the value the caller cannot see stays as it is.
                    continue;
                }
                columns.add(column);
                readPaths.add(readPath);
            }
            for (String key : uniqueKeys) {
                if (columns.stream().noneMatch(column -> covers(column, key))) {
                    return refused(new Refusal("Import template {0} has no column for {1}, which a row "
                            + "of the file is matched back to its record by.", name, key));
                }
            }
            return new Sheet(columns, readPaths, uniqueKeys, null);
        }

        /** A column carries a key field as itself, or — a relation in the key — as a lookup of it. */
        private static boolean covers(ImportFieldDTO column, String key) {
            String fieldName = column.getFieldName();
            return fieldName.equals(key) || fieldName.startsWith(key + ".");
        }

        ExportableImportTemplate describe(ImportTemplate template) {
            ExportableImportTemplate described = new ExportableImportTemplate();
            described.setId(template.getId());
            described.setName(template.getName());
            described.setCountry(StringUtils.trimToNull(template.getCountry()));
            described.setImportRule(template.getImportRule());
            described.setIgnoreEmpty(Boolean.TRUE.equals(template.getIgnoreEmpty()));
            described.setMatchHeaders(uniqueKeys.stream()
                    .map(key -> columns.stream().filter(column -> covers(column, key)).findFirst()
                            .map(ImportFieldDTO::getHeader).orElse(key))
                    .toList());
            described.setColumns(columns.stream()
                    .map(column -> new ExportableImportTemplate.Column(column.getHeader(),
                            Boolean.TRUE.equals(column.getRequired())))
                    .toList());
            return described;
        }
    }
}
