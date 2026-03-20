package io.softa.starter.file.entity;

import java.io.Serial;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.softa.framework.orm.entity.AuditableModel;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.enums.ImportStatus;
import io.softa.starter.file.enums.ImportType;

/**
 * ImportHistory Model
 */
@Data
@Schema(name = "ImportHistory")
@EqualsAndHashCode(callSuper = true)
public class ImportHistory extends AuditableModel {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "ID")
    private Long id;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "Template ID")
    private Long templateId;

    @Schema(description = "Model Name")
    private String modelName;

    @Schema(description = "Original File ID")
    private Long originalFileId;

    @Schema(description = "Import Type: Import / Validate")
    private ImportType importType;

    @Schema(description = "Import Rule: OnlyCreate / OnlyUpdate / CreateOrUpdate")
    private ImportRule importRule;

    @Schema(description = "Import Status")
    private ImportStatus status;

    @Schema(description = "Failed File ID")
    private Long failedFileId;

    @Schema(description = "Total Rows")
    private Integer totalRows;

    @Schema(description = "Success Rows")
    private Integer successRows;

    @Schema(description = "Failed Rows")
    private Integer failedRows;

    @Schema(description = "Duration in seconds")
    private Double duration;

    @Schema(description = "Error Message")
    private String errorMessage;

    @Schema(description = "Deleted")
    private Boolean deleted;

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    /**
     * Set error message, truncating to {@value MAX_ERROR_MESSAGE_LENGTH} characters if it exceeds the limit.
     *
     * @param errorMessage the error message
     */
    public void setErrorMessage(String errorMessage) {
        if (errorMessage != null && errorMessage.length() > MAX_ERROR_MESSAGE_LENGTH) {
            this.errorMessage = errorMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
        } else {
            this.errorMessage = errorMessage;
        }
    }
}