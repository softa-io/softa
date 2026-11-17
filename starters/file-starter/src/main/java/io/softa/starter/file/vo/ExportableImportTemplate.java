package io.softa.starter.file.vo;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.starter.file.enums.ImportRule;

/**
 * An import template a file for re-import can be exported with, described as the caller will get it:
 * the columns the file will carry for them, and what an import of it back will match rows by.
 */
@Data
@Schema(name = "ExportableImportTemplate",
        description = "An import template a file for re-import can be exported with, as the caller will get it")
public class ExportableImportTemplate {

    @Schema(description = "Import template id")
    private Long id;

    @Schema(description = "Template name")
    private String name;

    @Schema(description = "The country the template is written for, null for every country; "
            + "the file then holds that country's rows only")
    private String country;

    @Schema(description = "CreateOrUpdate or OnlyUpdate")
    private ImportRule importRule;

    @Schema(description = "Whether a blank cell keeps the current value when the file is imported back")
    private Boolean ignoreEmpty;

    @Schema(description = "Headers of the columns a row is matched back to its record by")
    private List<String> matchHeaders;

    @Schema(description = "The file's columns, in order")
    private List<Column> columns;

    /** One column of the file: its header, and whether the import requires a value in it. */
    public record Column(String header, boolean required) {
    }
}
