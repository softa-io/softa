package io.softa.starter.file.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.service.AccessScope;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.file.support.ImportTemplateCountryScope;
import io.softa.starter.file.service.ExportService;
import io.softa.starter.file.dto.SheetInfo;
import io.softa.starter.file.vo.ExportParams;
import io.softa.starter.file.vo.MultiSheetExportParams;

/**
 * ExportController
 */
@Tag(name = "Data Export")
@RestController
@RequestMapping("/export")
public class ExportController {

    @Autowired
    private ExportService exportService;

    @Autowired
    private ModelService<?> modelService;

    /**
     * How many of the rows the filters match the caller may export.
     *
     * <p>An export reads under the roles that hold the export action, which may reach fewer rows than
     * the roles that let the caller see the list: a role seeing every employee and another exporting
     * one department's leave the list showing hundreds and the file holding a handful. The export
     * dialog asks here so the number it shows is the number the file will have.
     *
     * @param modelName the model to be exported
     * @param exportParams the filters of the export; fields, orders and grouping are ignored
     * @return the number of rows an export with these filters would hold
     */
    @Operation(description = "Count the rows the caller may export with these filters.")
    @PostMapping(value = "/countExportable")
    public ApiResponse<Long> countExportable(@RequestParam String modelName,
                                             @RequestBody(required = false) ExportParams exportParams) {
        FlexQuery flexQuery = ExportParams.convertParamsToFlexQuery(exportParams);
        var filters = ImportTemplateCountryScope.forModel(modelName, flexQuery.getFilters());
        return ApiResponse.success(AccessScope.callAs(AccessType.EXPORT, () -> modelService.count(modelName, filters)));
    }

    /**
     * Export data by dynamic fields and ExportParams, without export template.
     * The convertType is set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param modelName the model name to be exported
     * @param exportParams the export parameters of the data to be exported
     * @return fileInfo object with download URL
     */
    @Operation(description = "Export data by dynamic fields and ExportParams, without export template.")
    @PostMapping(value = "/dynamicExport")
    public ApiResponse<FileInfo> dynamicExport(@RequestParam String modelName,
                                               @RequestBody ExportParams exportParams) {
        FlexQuery flexQuery = ExportParams.convertParamsToFlexQuery(exportParams);
        // The export of a list shows what the list shows; import templates state their own rule.
        flexQuery.setFilters(ImportTemplateCountryScope.forModel(modelName, flexQuery.getFilters()));
        return ApiResponse.success(exportService.dynamicExport(modelName, flexQuery, exportParams.getPivot()));
    }

    /**
     * Export data by exportTemplate configured exported fields or a custom file template.
     * The custom file template is a template file that contains the variables to be filled in.
     * The convertType is set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param exportTemplateId The ID of the export template
     * @param exportParams the export parameters of the data to be exported
     * @return fileInfo object with download URL
     */
    @Operation(description = "Export data by exportTemplate configured exported fields or a custom file template.")
    @PostMapping(value = "/exportByTemplate")
    @Parameter(name = "exportTemplateId", description = "The id of the ExportTemplate.")
    public ApiResponse<FileInfo> exportByTemplate(@RequestParam Long exportTemplateId,
                                                  @RequestBody ExportParams exportParams) {
        FlexQuery flexQuery = ExportParams.convertParamsToFlexQuery(exportParams);
        return ApiResponse.success(exportService.exportByTemplate(exportTemplateId, flexQuery));
    }

    /**
     * Export several objects into one workbook, a sheet each.
     *
     * <p>Exists because the objects hanging off a record are separate models: an employee's addresses,
     * family members and contacts are three queries, and asking for "this employee and everything
     * under them" means three sheets in one file rather than three downloads.
     *
     * <p>Each sheet should include its object's {@code code}, which is what an edited sheet is fed
     * back in by — a code updates the row it names, a blank one creates a new one.
     *
     * @param multiSheetExportParams the file name and one entry per object
     * @return fileInfo object with download URL
     */
    @Operation(description = "Export several models into one workbook, one sheet each.")
    @PostMapping(value = "/dynamicExportMultiSheet")
    public ApiResponse<FileInfo> dynamicExportMultiSheet(
            @RequestBody MultiSheetExportParams multiSheetExportParams) {
        Assert.notEmpty(multiSheetExportParams.getSheets(),
                "A multi-sheet export needs at least one sheet.");
        List<SheetInfo> sheetInfoList = new ArrayList<>();
        for (MultiSheetExportParams.Sheet sheet : multiSheetExportParams.getSheets()) {
            // The effective date belongs to the workbook. Left on a sheet it would be written to the
            // request context here and overwritten by the next sheet, so only the last one would take
            // effect — for every sheet. Said plainly rather than quietly ignored.
            Assert.isTrue(sheet.getExportParams() == null
                            || sheet.getExportParams().getEffectiveDate() == null,
                    "Set effectiveDate on the request, not on a sheet: it applies to the whole "
                            + "workbook and a per-sheet value cannot be honoured.");
            SheetInfo sheetInfo = new SheetInfo();
            sheetInfo.setModelName(sheet.getModelName());
            sheetInfo.setSheetName(sheet.getSheetName());
            FlexQuery sheetQuery = ExportParams.convertParamsToFlexQuery(sheet.getExportParams());
            sheetQuery.setFilters(ImportTemplateCountryScope.forModel(sheet.getModelName(), sheetQuery.getFilters()));
            sheetInfo.setFlexQuery(sheetQuery);
            // A sheet's pivot rides along: the main sheet of a workbook shows the same columns the
            // screen does, whether or not object sheets are ticked beside it.
            sheetInfo.setPivot(sheet.getExportParams() == null ? null : sheet.getExportParams().getPivot());
            sheetInfoList.add(sheetInfo);
        }
        // After the conversions, each of which cleared it: the queries all run later, so this is the
        // value they will see.
        ContextHolder.getContext().setEffectiveDate(multiSheetExportParams.getEffectiveDate());
        return ApiResponse.success(exportService.dynamicExportMultiSheet(
                multiSheetExportParams.getFileName(), sheetInfoList));
    }

}
