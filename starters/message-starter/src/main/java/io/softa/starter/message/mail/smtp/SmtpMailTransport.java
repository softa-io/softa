package io.softa.starter.message.mail.smtp;

import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.starter.message.mail.support.MailAddresses;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.enums.MailPriority;

/**
 * SMTP transport backed by Jakarta Mail via {@link JavaMailSenderImpl}.
 * <p>
 * Authenticated connections are reused across sends, one small pool per
 * {@link MailSendServerConfig} ({@code PooledSmtpSender}), so a run of mails
 * through one server pays the TCP connect, TLS handshake and AUTH once rather
 * than per message. A connection that cannot be reused is found and replaced
 * by itself: it is probed with {@code NOOP} before it is lent, and closed after
 * any failure, after 100 messages or after 30 seconds idle. A server whose
 * {@code connectionReuseEnabled} is off sends every mail on a connection of
 * its own.
 * <p>
 * The pools need no eviction hook on config writes: each is keyed by the
 * settings its connections were opened with, so an edited config opens fresh
 * connections on its next send, on every instance.
 * {@link io.softa.starter.message.mail.support.MailConfigCache} (Redis) stays
 * the single source of cached config.
 */
@Slf4j
@Component
public class SmtpMailTransport {

    public static final String NAME = "smtp";

    /** One pool per server config id, replaced when that config's connection settings change. */
    private final Map<Long, PooledSmtpSender> pools = new ConcurrentHashMap<>();

    /** {@link PooledSmtpSender#IDLE_TIMEOUT}; a field only so a test can make every connection idle at once. */
    private Duration poolIdleTimeout = PooledSmtpSender.IDLE_TIMEOUT;

    @Autowired(required = false)
    private FileService fileService;

    @Autowired
    private MessageProperties messageProperties;

    public SmtpSendResult send(MailSendServerConfig config, SmtpMailRequest request) {
        try {
            JavaMailSenderImpl sender = senderFor(config);
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
     * Build a configured, single-use {@link JavaMailSenderImpl} from {@code config}.
     * Public so {@code MailSendServerConfigServiceImpl.testConnectivity} can
     * reuse the exact same construction path as the live send path; it stays
     * unpooled, so a connectivity test always opens and authenticates a real
     * connection.
     */
    public JavaMailSenderImpl buildSender(MailSendServerConfig config) {
        return configure(new JavaMailSenderImpl(), config);
    }

    /**
     * The pooled sender for {@code config}, or a fresh single-use one when the
     * config is unsaved (no id to key a pool by — a connectivity test) or has
     * connection reuse turned off.
     */
    private JavaMailSenderImpl senderFor(MailSendServerConfig config) {
        if (config.getId() == null) {
            return buildSender(config);
        }
        if (Boolean.FALSE.equals(config.getConnectionReuseEnabled())) {
            // Reuse turned off for this server: close the pool it may still have.
            PooledSmtpSender stale = pools.remove(config.getId());
            if (stale != null) {
                stale.shutdown();
            }
            return buildSender(config);
        }
        PooledSmtpSender.Key key = PooledSmtpSender.Key.of(config);
        PooledSmtpSender[] replaced = new PooledSmtpSender[1];
        PooledSmtpSender sender = pools.compute(config.getId(), (id, current) -> {
            if (current != null && current.key().equals(key)) {
                return current;
            }
            replaced[0] = current;
            return configure(new PooledSmtpSender(key, poolIdleTimeout,
                    PooledSmtpSender.MAX_MESSAGES_PER_CONNECTION), config);
        });
        if (replaced[0] != null) {
            // Outside compute(): closing sends QUIT over the network.
            replaced[0].shutdown();
        }
        return sender;
    }

    /** Close pooled connections idle past {@link PooledSmtpSender#IDLE_TIMEOUT}. */
    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void evictIdleConnections() {
        pools.values().forEach(PooledSmtpSender::evictIdle);
    }

    @PreDestroy
    public void closePooledConnections() {
        pools.values().forEach(PooledSmtpSender::shutdown);
        pools.clear();
    }

    private <S extends JavaMailSenderImpl> S configure(S sender, MailSendServerConfig config) {
        sender.setHost(config.getHost());
        sender.setPort(config.getPort());
        sender.setUsername(config.getUsername());

        String protocol = config.getProtocol().getCode().toLowerCase();
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", protocol);
        props.put("mail.smtp.auth", true);
        props.put("mail.smtp.starttls.enable", Boolean.TRUE.equals(config.getStarttlsEnabled()));
        props.put("mail.smtp.ssl.enable", Boolean.TRUE.equals(config.getSslEnabled()));

        // Global transport timeouts: per-config tunability was YAGNI, see
        // MessageProperties.Transport Javadoc. Jakarta Mail reads them under the
        // protocol's own prefix: an SMTPS transport ignores mail.smtp.* and,
        // without mail.smtps.*, would wait on the server with no timeout at all.
        // The read timeout is also what bounds the NOOP probe of a pooled connection.
        String prefix = "mail." + protocol + ".";
        long connTimeoutMs = messageProperties.getMail().getTransport()
                .getConnectionTimeout().toMillis();
        long readTimeoutMs = messageProperties.getMail().getTransport()
                .getReadTimeout().toMillis();
        props.put(prefix + "connectiontimeout", connTimeoutMs);
        props.put(prefix + "timeout", readTimeoutMs);

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
            // RFC822 address-list: one or more mailboxes, comma-separated
            // (validated at acceptance by MailAddresses).
            message.setReplyTo(MailAddresses.parseAddressList(req.getReplyTo()));
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
                        "Attachments are not supported on this deployment: the file service module is not installed.");
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
