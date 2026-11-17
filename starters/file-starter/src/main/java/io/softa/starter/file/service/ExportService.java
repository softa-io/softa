package io.softa.starter.file.service;

import java.util.List;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.file.dto.ExportTemplateDTO;
import io.softa.starter.file.dto.SheetInfo;
import io.softa.starter.file.vo.ExportableImportTemplate;
import io.softa.starter.file.vo.PivotSpec;

public interface ExportService {

    /**
     * Export data by dynamic fields and ExportParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param modelName the model name to be exported
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    FileInfo dynamicExport(String modelName, FlexQuery flexQuery);

    /**
     * Same, with pivot columns appended from {@code pivot} — one per key of a related long table,
     * computed by the same rule the list view renders them with.
     */
    FileInfo dynamicExport(String modelName, FlexQuery flexQuery, PivotSpec pivot);

    /**
     * Export multiple sheets of data by dynamic fields and ExportParams, without export template.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param sheetInfoList the list of sheetInfo objects
     * @return fileInfo object with download URL
     */
    FileInfo dynamicExportMultiSheet(String fileName, List<SheetInfo> sheetInfoList);

    /**
     * Export data by exportTemplate configured exported fields.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param exportTemplateId the ID of the export template
     * @param flexQuery the flex query to be used for data retrieval
     * @return fileInfo object with download URL
     */
    FileInfo exportByTemplate(Long exportTemplateId, FlexQuery flexQuery);

    /**
     * The import templates of {@code modelName} a file for re-import can be exported with by the caller,
     * described as the caller will get them; empty when the caller cannot both export and import the model.
     *
     * @param modelName the model of the list the export dialog is opened on
     * @return the usable templates, in display order
     */
    List<ExportableImportTemplate> listImportTemplates(String modelName);

    /**
     * Export the selected rows into an import template's workbook — its columns, dropdowns and date
     * formats — to be edited and imported back with the same template.
     *
     * @param modelName        the model of the list; the template must be one of its own
     * @param importTemplateId the ID of the import template
     * @param flexQuery        the rows to export; its fields are the template's columns
     * @return fileInfo object with download URL
     */
    FileInfo exportByImportTemplate(String modelName, Long importTemplateId, FlexQuery flexQuery);

    /**
     * Export multiple sheets merged to on Excel file by specifying multi export templates.
     * The convertType should be set to DISPLAY to get the display values of the fields.
     * Such as displayName for ManyToOne/OneToOne fields, and label for Option fields.
     *
     * @param fileName the name of the Excel file to be exported
     * @param ids the list of export template id
     * @return fileInfo object with download URL
     */
    FileInfo exportByMultiTemplate(String fileName, List<Long> ids);

    FileInfo dynamicExportByMultiTemplate(String fileName, List<ExportTemplateDTO> dtoList);

}
