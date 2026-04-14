package io.softa.starter.message.mail.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Email attachment descriptor.
 * Provide either {@code data} (raw bytes) or {@code fileId} (from file-starter).
 */
@Data
@Schema(name = "MailAttachmentDTO")
public class MailAttachmentDTO {

    @Schema(description = "File name including extension")
    private String fileName;

    @Schema(description = "MIME content type, e.g. application/pdf")
    private String contentType;

    @Schema(description = "Raw attachment bytes (mutually exclusive with fileId)")
    private byte[] data;

    @Schema(description = "File ID from file-starter (mutually exclusive with data)")
    private Long fileId;
}
