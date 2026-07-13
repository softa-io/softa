package io.softa.starter.message.mail.smtp;

import java.util.List;
import lombok.Data;

import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.message.mail.enums.MailPriority;

/**
 * Internal SMTP send request assembled by the mail service layer.
 */
@Data
public class SmtpMailRequest {

    private String fromAddress;
    private String fromName;
    private List<String> to;
    private List<String> cc;
    private List<String> bcc;
    private String replyTo;
    private String subject;
    private String htmlBody;
    private String textBody;
    private List<FileInfo> attachments;
    private MailPriority priority;
    private boolean readReceiptRequested;
}
