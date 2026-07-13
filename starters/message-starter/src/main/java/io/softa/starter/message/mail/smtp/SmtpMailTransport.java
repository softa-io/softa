package io.softa.starter.message.mail.smtp;

import java.io.UnsupportedEncodingException;
import java.util.Properties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.enums.MailPriority;

/**
 * SMTP transport backed by Jakarta Mail via {@link JavaMailSenderImpl}.
 * <p>
 * Stateless by design: each {@link #send} constructs a fresh
 * {@link JavaMailSenderImpl} from the supplied {@link MailSendServerConfig}.
 * Construction is microsecond-cheap (a handful of setters and HashMap puts);
 * the real cost is the TLS handshake and AUTH paid on every send because the
 * underlying {@code Session} is single-use. Adding a Session/Transport cache
 * to amortize that cost is a future optimization — when it lands, that's the
 * time to introduce a tunable for the pool size.
 * <p>
 * The transport holds no instance state to invalidate:
 * {@link io.softa.starter.message.mail.support.MailConfigCache} (Redis) is the
 * single source of cached config, so config-change eviction only needs to hit
 * that one cache.
 */
@Slf4j
@Component
public class SmtpMailTransport {

    public static final String NAME = "smtp";

    @Autowired(required = false)
    private FileService fileService;

    @Autowired
    private MessageProperties messageProperties;

    public SmtpSendResult send(MailSendServerConfig config, SmtpMailRequest request) {
        try {
            JavaMailSenderImpl sender = buildSender(config);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = buildHelper(message, request);
            populate(helper, message, request);
            sender.send(message);
            return SmtpSendResult.success(message.getMessageID());
        } catch (MessagingException | UnsupportedEncodingException | MailException e) {
            log.warn("SmtpMailTransport: send failed for config id={}: {}", config.getId(), e.getMessage());
            return SmtpSendResult.failure(classify(e), e.getMessage());
        }
    }

    /**
     * Build a configured {@link JavaMailSenderImpl} from {@code config}.
     * Public so {@code MailSendServerConfigServiceImpl.testConnectivity} can
     * reuse the exact same construction path as the live send path.
     */
    public JavaMailSenderImpl buildSender(MailSendServerConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.getHost());
        sender.setPort(config.getPort());
        sender.setUsername(config.getUsername());

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", config.getProtocol().getCode().toLowerCase());
        props.put("mail.smtp.auth", true);
        props.put("mail.smtp.starttls.enable", Boolean.TRUE.equals(config.getStarttlsEnabled()));
        props.put("mail.smtp.ssl.enable", Boolean.TRUE.equals(config.getSslEnabled()));

        // Global transport timeouts: per-config tunability was YAGNI, see
        // MessageProperties.Transport Javadoc.
        long connTimeoutMs = messageProperties.getMail().getTransport()
                .getConnectionTimeout().toMillis();
        long readTimeoutMs = messageProperties.getMail().getTransport()
                .getReadTimeout().toMillis();
        props.put("mail.smtp.connectiontimeout", connTimeoutMs);
        props.put("mail.smtp.timeout", readTimeoutMs);

        if (messageProperties.getMail().isDebug()) {
            // softa.message.mail.debug=true — JavaMail writes the AUTH exchange to stdout, so
            // this must stay off in production.
            props.put("mail.debug", "true");
            log.warn("SmtpMailTransport: mail.debug=true — JavaMail will dump the SMTP AUTH "
                  + "exchange to stdout. Non-production only. config id={}", config.getId());
        }

        // Password authentication. Where a provider issues an API key as its SMTP
        // credential (e.g. SendGrid, SES), supply that key as the password.
        sender.setPassword(config.getPassword());

        return sender;
    }

    private MimeMessageHelper buildHelper(MimeMessage message, SmtpMailRequest req)
            throws MessagingException {
        boolean hasAttachments = !CollectionUtils.isEmpty(req.getAttachments());
        boolean multipart = hasAttachments
                || (StringUtils.hasText(req.getHtmlBody()) && StringUtils.hasText(req.getTextBody()));
        return new MimeMessageHelper(message, multipart, "UTF-8");
    }

    private void populate(MimeMessageHelper helper, MimeMessage message, SmtpMailRequest req)
            throws MessagingException, UnsupportedEncodingException {
        String fromName = StringUtils.hasText(req.getFromName())
                ? req.getFromName() : req.getFromAddress();
        helper.setFrom(req.getFromAddress(), fromName);

        helper.setTo(req.getTo().toArray(new String[0]));
        if (!CollectionUtils.isEmpty(req.getCc())) {
            helper.setCc(req.getCc().toArray(new String[0]));
        }
        if (!CollectionUtils.isEmpty(req.getBcc())) {
            helper.setBcc(req.getBcc().toArray(new String[0]));
        }
        if (StringUtils.hasText(req.getReplyTo())) {
            helper.setReplyTo(req.getReplyTo());
        }
        helper.setSubject(req.getSubject());

        if (req.isReadReceiptRequested()) {
            message.setHeader("Disposition-Notification-To", req.getFromAddress());
            message.setHeader("Return-Receipt-To", req.getFromAddress());
        }

        MailPriority priority = req.getPriority();
        if (priority != null) {
            message.setHeader("X-Priority", priority.getXPriority());
            message.setHeader("Importance", priority.getImportance());
            message.setHeader("X-MSMail-Priority", priority.getXMsMailPriority());
        }

        boolean hasHtml = StringUtils.hasText(req.getHtmlBody());
        boolean hasText = StringUtils.hasText(req.getTextBody());
        if (hasHtml && hasText) {
            helper.setText(req.getTextBody(), req.getHtmlBody());
        } else if (hasHtml) {
            helper.setText(req.getHtmlBody(), true);
        } else {
            helper.setText(req.getTextBody(), false);
        }

        if (!CollectionUtils.isEmpty(req.getAttachments())) {
            if (fileService == null) {
                throw new BusinessException(
                        "FileService is not on the classpath; cannot resolve attachment fileIds for SMTP send");
            }
            for (FileInfo attachment : req.getAttachments()) {
                Long fileId = attachment.getFileId();
                if (fileId == null) continue;
                String contentType = attachment.getFileType() != null
                        && !attachment.getFileType().getMimeTypeList().isEmpty()
                        ? attachment.getFileType().getMimeTypeList().getFirst()
                        : "application/octet-stream";
                // Stream from file-starter on demand: the lambda is invoked
                // when JavaMail serializes the MIME body. Each call returns a
                // fresh InputStream; bytes never fully materialise in JVM heap.
                helper.addAttachment(attachment.getFileName(),
                        () -> fileService.downloadStream(fileId),
                        contentType);
            }
        }
    }

    /**
     * Coarse classification. JavaMail exposes exception type but no standard
     * reply code. Downstream retry logic only needs a durable SMTP-prefixed
     * marker; detailed mapping lives in {@link io.softa.starter.message.shared.ErrorClassifier}.
     */
    private static String classify(Exception e) {
        return "SMTP_" + e.getClass().getSimpleName();
    }
}
