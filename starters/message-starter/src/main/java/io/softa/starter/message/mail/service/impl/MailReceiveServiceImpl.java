package io.softa.starter.message.mail.service.impl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import jakarta.mail.*;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.dto.UploadFileDTO;
import io.softa.framework.orm.enums.FileSource;
import io.softa.framework.orm.enums.FileType;
import io.softa.framework.orm.service.FileService;
import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailReceiveStatus;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.enums.ReceivedMailType;
import io.softa.starter.message.mail.service.MailReceiveRecordService;
import io.softa.starter.message.mail.service.MailReceiveService;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.support.BounceInfo;
import io.softa.starter.message.mail.support.MailClassification;
import io.softa.starter.message.mail.support.MailClassifier;
import io.softa.starter.message.mail.support.MailServerDispatcher;

/**
 * Implementation of {@link MailReceiveService}.
 * <p>
 * Connects to the resolved IMAP/POP3 server, fetches unseen messages,
 * classifies them (normal / read-receipt / bounce) via {@link MailClassifier},
 * persists them as {@link MailReceiveRecord} rows, and updates linked
 * {@link MailSendRecord} on bounce/receipt detection.
 */
@Slf4j
@Service
public class MailReceiveServiceImpl implements MailReceiveService {

    @Autowired
    private MailServerDispatcher dispatcher;

    @Autowired
    private MailReceiveRecordService recordService;

    @Autowired
    private MailSendRecordService sendRecordService;

    @Autowired
    private MailClassifier mailClassifier;

    @Autowired(required = false)
    private FileService fileService;

    @Override
    public int fetchNewMails() {
        MailReceiveServerConfig config = dispatcher.resolveReceive();
        return fetchFromServer(config);
    }

    @Override
    public int fetchNewMails(Long serverConfigId) {
        MailReceiveServerConfig config = dispatcher.resolveReceiveById(serverConfigId);
        return fetchFromServer(config);
    }

    @Override
    public void markAsRead(Long receiveRecordId) {
        MailReceiveRecord record = recordService.getById(receiveRecordId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "MailReceiveRecord {0} not found.", receiveRecordId));
        record.setStatus(MailReceiveStatus.READ);
        recordService.updateOne(record);
    }

    @Override
    public void markAsRead(List<Long> receiveRecordIds) {
        Filters filters = new Filters().in(MailReceiveRecord::getId, receiveRecordIds);
        MailReceiveRecord patch = new MailReceiveRecord();
        patch.setStatus(MailReceiveStatus.READ);
        recordService.updateByFilter(filters, patch);
    }

    // -------------------------------------------------
    // Fetch Logic
    // -------------------------------------------------

    private int fetchFromServer(MailReceiveServerConfig config) {
        String protocol = config.getProtocol().getCode().toLowerCase();
        Properties props = buildSessionProperties(config, protocol);
        Session session = Session.getInstance(props);
        int totalFetched = 0;

        try {
            Store store = session.getStore(protocol);

            try (store) {
                store.connect(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
                // Multi-folder support: parse fetchFolders, default to inboxFolder or "INBOX"
                List<String> folderNames = parseFolders(config);
                for (String folderName : folderNames) {
                    totalFetched += fetchFromFolder(store, folderName, config);
                }
            }
        } catch (MessagingException e) {
            throw new BusinessException("Failed to fetch mails from {0}:{1} — {2}",
                    config.getHost(), config.getPort(), e.getMessage(), e);
        }

        log.info("Fetched {} new email(s) from [{}:{}]", totalFetched, config.getHost(), config.getPort());
        return totalFetched;
    }

    private int fetchFromFolder(Store store, String folderName, MailReceiveServerConfig config) {
        int fetched = 0;
        try {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                log.debug("Folder '{}' does not exist on server [{}], skipping.", folderName, config.getHost());
                return 0;
            }
            folder.open(Folder.READ_ONLY);

            try {
                Message[] messages = folder.search(new jakarta.mail.search.FlagTerm(
                        new Flags(Flags.Flag.SEEN), false));
                for (Message message : messages) {
                    try {
                        MailReceiveRecord record = convertToRecord(message, config, folderName);
                        // Duplicate check
                        if (!isDuplicate(record.getMessageId(), config.getId())) {
                            // Phase-3: Archive EML original before persisting
                            archiveEml(message, record);

                            recordService.createOne(record);
                            fetched++;

                            // Process bounce/receipt linking
                            processClassification(record);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to process message in folder '{}': {}",
                                folderName, e.getMessage(), e);
                    }
                }
            } finally {
                folder.close(false);
            }
        } catch (MessagingException e) {
            log.warn("Failed to fetch from folder '{}' on [{}]: {}",
                    folderName, config.getHost(), e.getMessage());
        }
        return fetched;
    }

    /**
     * Parse the folder list from config. Supports:
     * <ol>
     *   <li>{@code fetchFolders} if set (comma-separated)</li>
     *   <li>Legacy {@code inboxFolder} field</li>
     *   <li>Default: "INBOX"</li>
     * </ol>
     */
    private List<String> parseFolders(MailReceiveServerConfig config) {
        if (StringUtils.hasText(config.getFetchFolders())) {
            return Arrays.stream(config.getFetchFolders().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
        String folder = StringUtils.hasText(config.getInboxFolder())
                ? config.getInboxFolder() : "INBOX";
        return List.of(folder);
    }

    // -------------------------------------------------
    // Record Conversion with Classification
    // -------------------------------------------------

    private MailReceiveRecord convertToRecord(Message message, MailReceiveServerConfig config, String folderName)
            throws MessagingException, IOException {
        MailReceiveRecord record = new MailReceiveRecord();
        record.setServerConfigId(config.getId());
        record.setFolderName(folderName);
        record.setSubject(message.getSubject());
        record.setFetchedAt(LocalDateTime.now());
        record.setStatus(MailReceiveStatus.UNREAD);

        if (message.getSentDate() != null) {
            record.setReceivedAt(message.getSentDate().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDateTime());
        }

        // Message-ID header — generate a synthetic dedup key when absent
        String[] msgIds = message.getHeader("Message-ID");
        if (msgIds != null && msgIds.length > 0) {
            record.setMessageId(msgIds[0]);
        } else {
            record.setMessageId(syntheticMessageId(message, config.getId(), folderName));
        }

        // Addresses
        record.setFromAddress(extractAddress(message.getFrom()));
        record.setToAddresses(extractAddresses(message.getRecipients(Message.RecipientType.TO)));
        record.setCcAddresses(extractAddresses(message.getRecipients(Message.RecipientType.CC)));

        // Body and attachments
        BodyExtractResult bodyResult = extractBody(message);
        record.setBody(bodyResult.body);
        record.setContentType(bodyResult.isHtml ? "HTML" : "TEXT");
        record.setHasAttachments(!bodyResult.attachmentNames.isEmpty());
        if (!bodyResult.attachmentNames.isEmpty()) {
            record.setAttachmentNames("[\"" + String.join("\",\"", bodyResult.attachmentNames) + "\"]");
        }

        // --- Phase-2: Mail classification ---
        if (message instanceof MimeMessage mimeMessage) {
            MailClassification classification = mailClassifier.classify(mimeMessage);
            record.setMailType(classification.getType().getCode());

            if (classification.getType() != ReceivedMailType.NORMAL) {
                record.setOriginalMessageId(classification.getOriginalMessageId());

                if (classification.getType() == ReceivedMailType.BOUNCE
                        && classification.getBounceInfo() != null) {
                    BounceInfo bi = classification.getBounceInfo();
                    record.setSmtpReplyCode(bi.getSmtpReplyCode());
                    record.setEnhancedStatusCode(bi.getEnhancedStatusCode());
                    record.setDiagnosticMessage(bi.getDiagnosticMessage());
                    if (!CollectionUtils.isEmpty(bi.getFailedRecipients())) {
                        record.setFailedRecipients(
                                "[\"" + String.join("\",\"", bi.getFailedRecipients()) + "\"]");
                    }
                }
            }
        } else {
            record.setMailType(ReceivedMailType.NORMAL.getCode());
        }

        return record;
    }

    // -------------------------------------------------
    // Bounce / Receipt → Send Record Linking
    // -------------------------------------------------

    /**
     * After saving a receive record, update the linked send record if this is
     * a bounce or read receipt.
     */
    private void processClassification(MailReceiveRecord record) {
        if (!StringUtils.hasText(record.getOriginalMessageId())) return;

        String mailType = record.getMailType();
        if (ReceivedMailType.READ_RECEIPT.getCode().equals(mailType)) {
            updateSendRecordForReceipt(record.getOriginalMessageId());
        } else if (ReceivedMailType.BOUNCE.getCode().equals(mailType)) {
            updateSendRecordForBounce(record);
        }
    }

    private void updateSendRecordForReceipt(String originalMessageId) {
        sendRecordService.findByMessageId(originalMessageId).ifPresent(sendRecord -> {
            sendRecord.setReadReceiptReceived(true);
            sendRecord.setReadReceiptReceivedAt(LocalDateTime.now());
            sendRecordService.updateOne(sendRecord);
            log.info("Read receipt received for send record id={}, Message-ID={}",
                    sendRecord.getId(), originalMessageId);
        });
    }

    private void updateSendRecordForBounce(MailReceiveRecord receiveRecord) {
        sendRecordService.findByMessageId(receiveRecord.getOriginalMessageId()).ifPresent(sendRecord -> {
            sendRecord.setBounced(true);
            // Compose bounce code summary, e.g. "550 5.1.1"
            String bounceCode = StringUtils.hasText(receiveRecord.getSmtpReplyCode())
                    ? receiveRecord.getSmtpReplyCode() : "";
            if (StringUtils.hasText(receiveRecord.getEnhancedStatusCode())) {
                bounceCode = bounceCode.isEmpty()
                        ? receiveRecord.getEnhancedStatusCode()
                        : bounceCode + " " + receiveRecord.getEnhancedStatusCode();
            }
            if (StringUtils.hasText(bounceCode)) {
                sendRecord.setBounceCode(bounceCode.trim());
            }
            sendRecord.setStatus(MailSendStatus.FAILED);
            sendRecordService.updateOne(sendRecord);
            log.info("Bounce detected for send record id={}, code={}, Message-ID={}",
                    sendRecord.getId(), bounceCode, receiveRecord.getOriginalMessageId());
        });
    }

    // -------------------------------------------------
    // EML Archival (Phase-3)
    // -------------------------------------------------

    /**
     * Archive the raw EML content of a message via {@link FileService}.
     * If file-starter is not on the classpath ({@code fileService == null}),
     * this method is a no-op.
     */
    private void archiveEml(Message message, MailReceiveRecord record) {
        if (fileService == null) return;
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

    // -------------------------------------------------
    // Helpers
    // -------------------------------------------------

    private boolean isDuplicate(String messageId, Long serverConfigId) {
        if (!StringUtils.hasText(messageId)) return false;
        Filters filters = new Filters()
                .eq(MailReceiveRecord::getMessageId, messageId)
                .eq(MailReceiveRecord::getServerConfigId, serverConfigId);
        return recordService.count(filters) > 0;
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

    private String extractAddresses(Address[] addresses) {
        if (addresses == null || addresses.length == 0) return null;
        List<String> list = new ArrayList<>();
        for (Address a : addresses) {
            list.add(((InternetAddress) a).getAddress());
        }
        return "[\"" + String.join("\",\"", list) + "\"]";
    }

    private BodyExtractResult extractBody(Part part) throws MessagingException, IOException {
        BodyExtractResult result = new BodyExtractResult();
        if (part.isMimeType("text/plain")) {
            result.body = (String) part.getContent();
            result.isHtml = false;
        } else if (part.isMimeType("text/html")) {
            result.body = (String) part.getContent();
            result.isHtml = true;
        } else if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                Part bodyPart = mp.getBodyPart(i);
                String disposition = bodyPart.getDisposition();
                if (Part.ATTACHMENT.equalsIgnoreCase(disposition)
                        || Part.INLINE.equalsIgnoreCase(disposition)) {
                    if (StringUtils.hasText(bodyPart.getFileName())) {
                        result.attachmentNames.add(bodyPart.getFileName());
                    }
                } else {
                    BodyExtractResult sub = extractBody(bodyPart);
                    if (StringUtils.hasText(sub.body)) {
                        result.body = sub.body;
                        result.isHtml = sub.isHtml;
                    }
                    result.attachmentNames.addAll(sub.attachmentNames);
                }
            }
        }
        return result;
    }

    private Properties buildSessionProperties(MailReceiveServerConfig config, String protocol) {
        Properties props = new Properties();
        props.put("mail." + protocol + ".host", config.getHost());
        props.put("mail." + protocol + ".port", config.getPort());
        props.put("mail." + protocol + ".ssl.enable", Boolean.TRUE.equals(config.getSslEnabled()));
        int connTimeout = config.getConnectionTimeoutMs() != null ? config.getConnectionTimeoutMs() : 5000;
        int readTimeout = config.getReadTimeoutMs() != null ? config.getReadTimeoutMs() : 30000;
        props.put("mail." + protocol + ".connectiontimeout", connTimeout);
        props.put("mail." + protocol + ".timeout", readTimeout);
        return props;
    }

    private static class BodyExtractResult {
        String body;
        boolean isHtml;
        List<String> attachmentNames = new ArrayList<>();
    }
}
