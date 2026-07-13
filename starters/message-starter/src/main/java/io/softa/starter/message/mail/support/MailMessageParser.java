package io.softa.starter.message.mail.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.framework.base.utils.HtmlUtils;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;
import io.softa.framework.orm.enums.FileSource;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.enums.MailReceiveStatus;
import io.softa.starter.message.mail.enums.ReceivedMailType;
import io.softa.starter.message.mail.enums.TruncationReason;

/**
 * Converts a fetched jakarta.mail {@link Message} into a {@link MailReceiveRecord}:
 * MIME body extraction (with structural defenses), attachment upload,
 * classification, address parsing, dedup-key synthesis, and optional raw-EML
 * archival.
 * <p>
 * Extracted from {@code MailReceiveServiceImpl} so the receive service stays a
 * thin protocol-fetch orchestrator and this — the most logic-dense,
 * file/classifier-dependent part — is directly unit-testable without reflection.
 */
@Slf4j
@Component
public class MailMessageParser {

    @Autowired(required = false)
    private FileService fileService;

    @Autowired
    private MailClassifier mailClassifier;

    @Autowired
    private MessageProperties messageProperties;

    /**
     * Convert a message to a {@link MailReceiveRecord}. Messages whose reported
     * size exceeds {@code maxMessageSize} are persisted envelope-only (no body
     * fetch, no memory blow-up) with {@code truncationReason = BodyTooLarge}.
     */
    public MailReceiveRecord parse(Message message, MailReceiveServerConfig config, String folderName)
            throws MessagingException, IOException {
        long maxBytes = messageProperties.getMail().getFetch().getMaxMessageSize().toBytes();
        long size = message.getSize();
        if (size > 0 && size > maxBytes) {
            log.warn("Message size={} exceeds maxMessageSize={} on folder '{}', persisting envelope only",
                    size, maxBytes, folderName);
            MailReceiveRecord record = toEnvelopeOnlyRecord(message, config, folderName);
            record.setTruncationReason(TruncationReason.BodyTooLarge.name());
            return record;
        }
        return toFullRecord(message, config, folderName);
    }

    /**
     * Archive the raw EML content of a message via {@link FileService}.
     * No-op when {@code fileService} is unavailable or when the
     * {@code archiveEml} flag is disabled — callers can invoke unconditionally.
     */
    public void archiveEml(Message message, MailReceiveRecord record) {
        if (fileService == null) return;
        if (!messageProperties.getMail().getFetch().isArchiveEml()) return;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            message.writeTo(baos);
            byte[] emlBytes = baos.toByteArray();

            String fileName = StringUtils.hasText(record.getMessageId())
                    ? record.getMessageId().replaceAll("[<>]", "")
                    : "mail_" + System.currentTimeMillis();

            UploadFileDTO uploadDTO = new UploadFileDTO();
            uploadDTO.setModelName("MailReceiveRecord");
            uploadDTO.setFileName(fileName);
            uploadDTO.setFileType(FileType.EML);
            uploadDTO.setFileSize(emlBytes.length / 1024);
            uploadDTO.setFileSource(FileSource.DOWNLOAD);
            uploadDTO.setInputStream(new ByteArrayInputStream(emlBytes));

            FileInfo fileInfo = fileService.uploadFromStream(uploadDTO);
            record.setEmlFileId(fileInfo.getFileId());

            log.debug("Archived EML for Message-ID={}, fileId={}",
                    record.getMessageId(), fileInfo.getFileId());
        } catch (Exception e) {
            log.warn("Failed to archive EML for Message-ID={}: {}",
                    record.getMessageId(), e.getMessage());
            // Non-fatal: continue processing even if archival fails
        }
    }

    /**
     * Populate the envelope fields shared by {@link #toEnvelopeOnlyRecord} and {@link #toFullRecord}:
     * server / folder / subject / fetch-time / status, the sent→received-at conversion, the Message-ID
     * (synthetic when the header is absent), and the from / to / cc addresses.
     */
    private void populateEnvelope(MailReceiveRecord record, Message message,
                                  MailReceiveServerConfig config, String folderName)
            throws MessagingException {
        record.setServerConfigId(config.getId());
        record.setFolderName(folderName);
        record.setSubject(message.getSubject());
        record.setFetchedAt(LocalDateTime.now());
        record.setStatus(MailReceiveStatus.UNREAD);
        if (message.getSentDate() != null) {
            record.setReceivedAt(message.getSentDate().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDateTime());
        }
        // Message-ID header — generate a synthetic dedup key when absent.
        String[] msgIds = message.getHeader("Message-ID");
        if (msgIds != null && msgIds.length > 0) {
            record.setMessageId(msgIds[0]);
        } else {
            record.setMessageId(syntheticMessageId(message, config.getId(), folderName));
        }
        record.setFromAddress(extractAddress(message.getFrom()));
        record.setToAddresses(extractAddresses(message.getRecipients(Message.RecipientType.TO)));
        record.setCcAddresses(extractAddresses(message.getRecipients(Message.RecipientType.CC)));
    }

    /**
     * Build an envelope-only record without invoking {@code message.getContent()}.
     * Used when {@code maxMessageSize} is exceeded — we want to preserve the fact
     * that this email arrived (for audit / observability) without paying the
     * body-fetch cost or risking memory blow-up.
     */
    private MailReceiveRecord toEnvelopeOnlyRecord(
            Message message, MailReceiveServerConfig config, String folderName)
            throws MessagingException {
        MailReceiveRecord record = new MailReceiveRecord();
        populateEnvelope(record, message, config, folderName);
        record.setMailType(ReceivedMailType.NORMAL);
        return record;
    }

    private MailReceiveRecord toFullRecord(Message message, MailReceiveServerConfig config, String folderName)
            throws MessagingException, IOException {
        MailReceiveRecord record = new MailReceiveRecord();
        populateEnvelope(record, message, config, folderName);

        // Body and attachments — extract plain and HTML independently. The
        // bodyMode field captures the original wire shape (HTML/PLAIN/MIXED)
        // *before* any derivation, so audit/forensics can always tell whether
        // the sender shipped a real text/plain part. After mode is captured,
        // bodyText is derived from bodyHtml when the sender only sent HTML —
        // doing this at write time keeps list/search reads O(1); deriving
        // lazily at API response time would mean re-running HtmlUtils.toText
        // on every list paint, which doesn't scale.
        // MIME-structure limit hits abort extraction and surface as
        // truncationReason; on hit we drop any partially-collected attachment
        // refs so the record doesn't claim ownership of files that may or may
        // not have made it to file-starter.
        BodyExtractResult bodyResult = new BodyExtractResult();
        try {
            extractBody(message, bodyResult, 0);
        } catch (MimeBoundaryException e) {
            log.warn("MIME structure limit hit ({}), aborting body extraction for messageId={}",
                    e.getReason(), record.getMessageId());
            record.setTruncationReason(e.getReason().name());
            bodyResult.bodyText = null;
            bodyResult.bodyHtml = null;
            bodyResult.attachments.clear();
        }
        // Capture bodyMode from the *raw* extraction result, before deriving
        // bodyText from HTML — otherwise an HTML-only email would look like
        // HTML_WITH_PLAIN_ALT after derivation.
        record.setBodyMode(detectBodyMode(bodyResult.bodyText, bodyResult.bodyHtml));
        if (bodyResult.bodyText == null && bodyResult.bodyHtml != null) {
            bodyResult.bodyText = HtmlUtils.toText(bodyResult.bodyHtml);
        }
        record.setBodyText(bodyResult.bodyText);
        record.setBodyHtml(bodyResult.bodyHtml);
        record.setAttachments(
                bodyResult.attachments.isEmpty() ? null : bodyResult.attachments);
        if (record.getTruncationReason() == null
                && bodyResult.firstAttachmentTruncation != null) {
            record.setTruncationReason(bodyResult.firstAttachmentTruncation.name());
        }

        // --- Mail classification: primary type + orthogonal flags ---
        if (message instanceof MimeMessage mimeMessage) {
            MailClassification classification = mailClassifier.classify(mimeMessage);
            record.setMailType(classification.getType());
            record.setIsMailingList(classification.isMailingList());
            record.setIsEncrypted(classification.isEncrypted());
            record.setIsSpam(classification.isSpam());

            // Only READ_RECEIPT and BOUNCE drive automated linkage back to the
            // originating MailSendRecord. AUTO_REPLY / CALENDAR_INVITE / NORMAL
            // / UNKNOWN are recorded for observability but do not trigger
            // send-record updates. Orthogonal flags above apply regardless.
            if (classification.getType() == ReceivedMailType.READ_RECEIPT
                    || classification.getType() == ReceivedMailType.BOUNCE) {
                record.setOriginalMessageId(classification.getOriginalMessageId());

                if (classification.getType() == ReceivedMailType.BOUNCE
                        && classification.getBounceInfo() != null) {
                    BounceInfo bi = classification.getBounceInfo();
                    record.setSmtpReplyCode(bi.getSmtpReplyCode());
                    record.setEnhancedStatusCode(bi.getEnhancedStatusCode());
                    record.setDiagnosticMessage(bi.getDiagnosticMessage());
                    if (!CollectionUtils.isEmpty(bi.getFailedRecipients())) {
                        record.setFailedRecipients(bi.getFailedRecipients());
                    }
                }
            }
        } else {
            record.setMailType(ReceivedMailType.NORMAL);
        }

        return record;
    }

    /**
     * Generates a deterministic dedup key for messages that lack a {@code Message-ID} header.
     * Uses SHA-256 of (serverConfigId + folderName + from + subject + sentDate millis).
     * Prefixed with {@code "synthetic:"} to distinguish from real Message-IDs.
     */
    private String syntheticMessageId(Message message, Long serverConfigId, String folderName)
            throws MessagingException {
        String from = message.getFrom() != null && message.getFrom().length > 0
                ? message.getFrom()[0].toString() : "";
        String subject = message.getSubject() != null ? message.getSubject() : "";
        String date = message.getSentDate() != null
                ? String.valueOf(message.getSentDate().getTime()) : "";
        String raw = serverConfigId + ":" + folderName + ":" + from + ":" + subject + ":" + date;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "synthetic:" + hex;
        } catch (Exception e) {
            return "synthetic:" + Math.abs(raw.hashCode());
        }
    }

    private String extractAddress(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return null;
        return ((InternetAddress) addresses[0]).getAddress();
    }

    private List<String> extractAddresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return null;
        List<String> list = new ArrayList<>();
        for (Address a : addresses) {
            list.add(((InternetAddress) a).getAddress());
        }
        return list;
    }

    /**
     * Map the observed body parts to a {@link BodyMode}. Captures the wire
     * shape: presence of {@code bodyHtml} alone is HTML-only, presence of
     * both is multipart/alternative, plain alone is text-only, neither is a
     * pure-attachment email (calendar invite, encrypted body, etc.).
     *
     * <p>For incoming {@code multipart/alternative}, the sender's plain part
     * is treated as canonical content — we record it as
     * {@link BodyMode#HTML_WITH_AUTHORED_PLAIN}, never as DERIVED. Whether
     * the sender themselves auto-stripped or hand-wrote the plain part is
     * unknowable from our side; from the recipient's perspective both are
     * authored content shipped to us.
     */
    private static BodyMode detectBodyMode(String bodyText, String bodyHtml) {
        if (bodyHtml != null && bodyText != null) return BodyMode.HTML_WITH_AUTHORED_PLAIN;
        if (bodyHtml != null) return BodyMode.HTML;
        if (bodyText != null) return BodyMode.PLAIN;
        return null;
    }

    /**
     * Walk the MIME tree, accumulating the first text/plain part into
     * {@code bodyText}, the first text/html part into {@code bodyHtml}, and
     * named attachments into {@code attachments}. First-wins for each
     * format keeps {@code multipart/alternative} ordering-independent.
     *
     * <p>Two structural defenses are enforced via {@code maxMimeDepth} and
     * {@code maxMimeParts}: a hit on either throws {@link MimeBoundaryException}
     * to abort the walk; the caller turns that into a {@code truncationReason}
     * on the record.
     */
    private void extractBody(Part part, BodyExtractResult acc, int depth)
            throws MessagingException, IOException {
        int maxDepth = messageProperties.getMail().getFetch().getMaxMimeDepth();
        int maxParts = messageProperties.getMail().getFetch().getMaxMimeParts();
        if (depth > maxDepth) {
            throw new MimeBoundaryException(TruncationReason.MimeDepthExceeded);
        }
        if (++acc.partCount > maxParts) {
            throw new MimeBoundaryException(TruncationReason.MimePartsExceeded);
        }
        if (part.isMimeType("text/plain")) {
            if (acc.bodyText == null) acc.bodyText = (String) part.getContent();
        } else if (part.isMimeType("text/html")) {
            if (acc.bodyHtml == null) acc.bodyHtml = (String) part.getContent();
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                Part bodyPart = mp.getBodyPart(i);
                String disposition = bodyPart.getDisposition();
                if (Part.ATTACHMENT.equalsIgnoreCase(disposition)
                        || Part.INLINE.equalsIgnoreCase(disposition)) {
                    String fileName = bodyPart.getFileName();
                    if (StringUtils.hasText(fileName)) {
                        FileInfo info = uploadAttachment(bodyPart, fileName, acc);
                        if (info != null) acc.attachments.add(info);
                    }
                } else {
                    extractBody(bodyPart, acc, depth + 1);
                }
            }
        }
    }

    /**
     * Upload one attachment {@link Part} to {@link FileService} and return the
     * resulting {@link FileInfo}. Returns {@code null} when {@code fileService}
     * isn't on the classpath, the part exceeds {@code maxAttachmentSize}, or the
     * upload itself fails. Skipped attachments do not appear in
     * {@code MailReceiveRecord.attachments}; the
     * {@code AttachmentTooLarge} truncation reason is set so audit can find them.
     */
    private FileInfo uploadAttachment(Part part, String fileName, BodyExtractResult acc) {
        if (fileService == null) return null;
        long maxAttachBytes = messageProperties.getMail().getFetch().getMaxAttachmentSize().toBytes();
        try {
            int reportedSize = part.getSize();
            if (reportedSize > 0 && reportedSize > maxAttachBytes) {
                log.warn("Attachment '{}' size={} exceeds maxAttachmentSize={}, skipping upload",
                        fileName, reportedSize, maxAttachBytes);
                if (acc.firstAttachmentTruncation == null) {
                    acc.firstAttachmentTruncation = TruncationReason.AttachmentTooLarge;
                }
                return null;
            }
            byte[] data;
            try (InputStream is = part.getInputStream()) {
                data = is.readAllBytes();
            }

            String mimeType = part.getContentType();
            if (mimeType != null) {
                int sep = mimeType.indexOf(';');
                if (sep >= 0) mimeType = mimeType.substring(0, sep).trim();
            }
            FileType fileType = resolveFileType(mimeType, fileName);
            if (fileType == null) {
                log.warn("Unknown file type for attachment '{}' (mime={}); skipping upload",
                        fileName, mimeType);
                return null;
            }

            UploadFileDTO uploadDTO = new UploadFileDTO();
            uploadDTO.setModelName("MailReceiveRecord");
            uploadDTO.setFileName(fileName);
            uploadDTO.setFileType(fileType);
            uploadDTO.setFileSize(data.length / 1024);
            uploadDTO.setFileSource(FileSource.DOWNLOAD);
            uploadDTO.setInputStream(new ByteArrayInputStream(data));

            return fileService.uploadFromStream(uploadDTO);
        } catch (Exception e) {
            log.warn("Failed to upload attachment '{}': {}", fileName, e.getMessage());
            return null;
        }
    }

    /**
     * Map MIME type → {@link FileType}, falling back to the file extension when
     * the MIME type isn't recognized. Returns null when neither can be mapped.
     */
    private static FileType resolveFileType(String mimeType, String fileName) {
        if (StringUtils.hasText(mimeType)) {
            Optional<FileType> byMime = FileType.of(mimeType);
            if (byMime.isPresent()) return byMime.get();
        }
        if (StringUtils.hasText(fileName)) {
            int dot = fileName.lastIndexOf('.');
            if (dot >= 0 && dot < fileName.length() - 1) {
                String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
                return FileType.ofExtension(ext).orElse(null);
            }
        }
        return null;
    }

    private static class BodyExtractResult {
        String bodyText;
        String bodyHtml;
        List<FileInfo> attachments = new ArrayList<>();
        int partCount;
        TruncationReason firstAttachmentTruncation;
    }

    /**
     * Thrown by {@link #extractBody} when a MIME structural limit is exceeded.
     * Carries the specific {@link TruncationReason} so the caller can persist it
     * on {@code MailReceiveRecord.truncationReason}.
     */
    private static class MimeBoundaryException extends MessagingException {
        private final TruncationReason reason;

        MimeBoundaryException(TruncationReason reason) {
            super("MIME structure limit exceeded: " + reason);
            this.reason = reason;
        }

        TruncationReason getReason() {
            return reason;
        }
    }
}
