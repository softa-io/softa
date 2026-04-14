package io.softa.starter.message.mail.dto;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import io.softa.starter.message.mail.enums.MailPriority;

/**
 * Request payload for sending an email.
 * <p>
 * Supports three modes:
 * <ul>
 *   <li><b>Single / uniform batch:</b> set {@code to} list + {@code subject} + body fields
 *       → same email to all recipients (single MIME message)</li>
 *   <li><b>Differentiated batch:</b> set {@code items} with per-recipient subject/body/variables
 *       → one email per item, each with its own content</li>
 *   <li><b>Template-based:</b> set {@code templateCode} + {@code templateVariables} (or per-item variables)
 *       → template is resolved and rendered</li>
 * </ul>
 */
@Data
@Schema(name = "SendMailDTO")
public class SendMailDTO {

    private static final int MAX_BATCH_SIZE = 500;

    @Schema(description = "Primary recipients (for uniform send)")
    private List<String> to;

    @Schema(description = "CC recipients")
    private List<String> cc;

    @Schema(description = "BCC recipients")
    private List<String> bcc;

    @Schema(description = "Email subject")
    private String subject;

    @Schema(description = "Plain-text body (used when htmlBody is absent)")
    private String textBody;

    @Schema(description = "HTML body (takes priority over textBody when both are provided)")
    private String htmlBody;

    @Schema(description = "Attachments")
    private List<MailAttachmentDTO> attachments;

    @Schema(description = "Explicit server config ID; null = auto-resolved via MailServerDispatcher")
    private Long serverConfigId;

    @Schema(description = "Reply-to address override")
    private String replyTo;

    @Schema(description = "Whether to request a read receipt (overrides server config default)")
    private Boolean readReceiptRequested;

    @Schema(description = "Email priority (overrides template default). Sets X-Priority, Importance, and X-MSMail-Priority headers.")
    private MailPriority priority;

    @Schema(description = "Template code for template-based sending (e.g. USER_WELCOME)")
    private String templateCode;

    @Schema(description = "Template placeholder variables")
    private Map<String, Object> templateVariables;

    @Size(max = MAX_BATCH_SIZE)
    @Schema(description = "Per-recipient differentiated batch items. "
            + "When set, each item can carry its own to/subject/body or templateVariables. "
            + "One email per item is sent independently.")
    private List<BatchMailItemDTO> items;
}
