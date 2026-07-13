package io.softa.starter.message.mail.dto;

import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.enums.MailPriority;

/**
 * Request payload for sending an email.
 * <p>
 * Represents one email message. Multiple {@code to} recipients are addressed
 * in the same MIME message; independent messages use
 * {@code MessageService.sendMailBatch(List)}.
 * <ul>
 *   <li><b>Direct:</b> set {@code to} + {@code subject} + body fields</li>
 *   <li><b>Template-based:</b> set {@code templateCode} + {@code templateVariables}
 *       → template is resolved and rendered</li>
 * </ul>
 */
@Data
@Schema(name = "SendMailDTO")
public class SendMailDTO {

    @Schema(description = "Primary recipients addressed in one email")
    private List<String> to;

    @Schema(description = "CC recipients")
    private List<String> cc;

    @Schema(description = "BCC recipients")
    private List<String> bcc;

    @Schema(description = "Email subject")
    private String subject;

    @Schema(description = "Plain-text body. Combined with htmlBody to form multipart/alternative.")
    private String textBody;

    @Schema(description = "HTML body. Combined with textBody to form multipart/alternative.")
    private String htmlBody;

    @Schema(description = "Caller-declared intent for how the bodies should be interpreted and persisted. "
            + "Optional — when null, MessageService infers from which body fields are populated. "
            + "Set explicitly when you need to distinguish HTML_WITH_DERIVED_PLAIN (auto-extracted at send) "
            + "from HTML_WITH_AUTHORED_PLAIN (independently human-authored), since both produce DTOs "
            + "with both htmlBody and textBody populated.")
    private BodyMode bodyMode;

    @Schema(description = "Attachments — references to files already in file-starter. "
            + "Caller uploads bytes via FileService first, then passes the resulting FileInfo here. "
            + "Empty/null defers to template defaults (if any).")
    private List<FileInfo> attachments;

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

}
