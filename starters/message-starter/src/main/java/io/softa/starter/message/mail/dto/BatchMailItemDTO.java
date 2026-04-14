package io.softa.starter.message.mail.dto;

import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotEmpty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Per-recipient item in a differentiated batch mail send.
 * <p>
 * Each item specifies recipient(s) with their own subject/body or template variables,
 * enabling different emails per recipient in a single batch call.
 * <p>
 * Content resolution priority:
 * <ol>
 *   <li>If {@code htmlBody} or {@code textBody} is set, it is used directly</li>
 *   <li>Otherwise, the parent {@link SendMailDTO}'s template is rendered with
 *       this item's {@code templateVariables}</li>
 *   <li>Fallback to parent {@code htmlBody}/{@code textBody}</li>
 * </ol>
 * Subject resolution:
 * <ol>
 *   <li>If {@code subject} is set, it is used directly</li>
 *   <li>Otherwise, the parent template subject is rendered with this item's {@code templateVariables}</li>
 *   <li>Fallback to parent {@code subject}</li>
 * </ol>
 */
@Data
@Schema(name = "BatchMailItemDTO")
public class BatchMailItemDTO {

    @NotEmpty
    @Schema(description = "Primary recipients for this item", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> to;

    @Schema(description = "CC recipients for this item (overrides parent cc)")
    private List<String> cc;

    @Schema(description = "BCC recipients for this item (overrides parent bcc)")
    private List<String> bcc;

    @Schema(description = "Subject for this recipient (overrides parent/template)")
    private String subject;

    @Schema(description = "HTML body for this recipient (overrides template rendering)")
    private String htmlBody;

    @Schema(description = "Plain-text body for this recipient (overrides template rendering)")
    private String textBody;

    @Schema(description = "Per-recipient template placeholder variables (used with parent template code)")
    private Map<String, Object> templateVariables;
}

