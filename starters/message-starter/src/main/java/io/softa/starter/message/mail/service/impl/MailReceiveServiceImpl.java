package io.softa.starter.message.mail.service.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import jakarta.mail.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailFetchImapWatermark;
import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.entity.MailReceiveServerConfig;
import io.softa.starter.message.mail.enums.*;
import io.softa.starter.message.mail.service.MailFetchImapWatermarkService;
import io.softa.starter.message.mail.service.MailReceiveRecordService;
import io.softa.starter.message.mail.service.MailReceiveService;
import io.softa.starter.message.mail.support.BounceReceiptLinker;
import io.softa.starter.message.mail.support.MailMessageParser;
import io.softa.starter.message.mail.support.MailServerDispatcher;

/**
 * Implementation of {@link MailReceiveService}.
 * <p>
 * Thin protocol-fetch orchestrator: connects to the resolved IMAP/POP3 server,
 * pulls unseen messages, and for each one delegates the heavy lifting to two
 * collaborators — {@link MailMessageParser} (MIME → {@link MailReceiveRecord})
 * and {@link BounceReceiptLinker} (read-receipt / bounce → originating
 * {@code MailSendRecord}). This class owns only the connect / per-folder fetch
 * lifecycle (IMAP watermark+lease, POP3 destructive drain), dedup, and persist.
 */
@Slf4j
@Service
public class MailReceiveServiceImpl implements MailReceiveService {

    @Autowired
    private MailServerDispatcher dispatcher;

    @Autowired
    private MailReceiveRecordService recordService;

    @Autowired
    private MailMessageParser mailMessageParser;

    @Autowired
    private BounceReceiptLinker bounceReceiptLinker;

    @Autowired
    private MailFetchImapWatermarkService watermarkService;

    @Autowired
    private MessageProperties messageProperties;

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
                store.connect(config.getHost(), config.getPort(),
                        config.getUsername(), config.getPassword());
                // Multi-folder support: parse fetchFolders, default to "INBOX"
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
        if (config.getProtocol().isImap()) {
            return fetchFromImapFolder(store, folderName, config);
        }
        return fetchFromPop3Folder(store, folderName, config);
    }

    /**
     * IMAP path: non-destructive incremental fetch driven by per-(config, folder)
     * UID watermark. Acquires a soft lease before fetching to prevent two workers
     * from processing the same (config, folder) under the Shared Pulsar
     * subscription. UIDVALIDITY change resets the watermark to 0 — duplicate
     * Message-IDs are still caught by {@link #isDuplicate}.
     */
    private int fetchFromImapFolder(Store store, String folderName, MailReceiveServerConfig config) {
        Optional<MailFetchImapWatermark> leaseOpt = watermarkService.tryAcquireLease(
                config.getId(), folderName,
                messageProperties.getMail().getFetch().getLeaseTimeout());
        if (leaseOpt.isEmpty()) {
            return 0;
        }
        MailFetchImapWatermark watermark = leaseOpt.get();
        // Lease token: each successful CAS transition we perform bumps it by one.
        long leaseVersion = watermark.getVersion() != null ? watermark.getVersion() : 0L;
        int fetched = 0;
        Long maxUidProcessed = null;

        try {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                log.debug("Folder '{}' does not exist on server [{}], skipping.",
                        folderName, config.getHost());
                watermarkService.releaseSuccess(watermark.getId(), leaseVersion, null);
                return 0;
            }
            folder.open(Folder.READ_ONLY);
            try {
                UIDFolder uidFolder = (UIDFolder) folder;
                long currentValidity = uidFolder.getUIDValidity();
                long startUid;
                if (watermark.getUidValidity() != null
                        && watermark.getUidValidity() != currentValidity) {
                    log.warn("UIDVALIDITY changed for config={} folder={} (was {}, now {}); "
                                    + "resetting watermark.",
                            config.getId(), folderName,
                            watermark.getUidValidity(), currentValidity);
                    if (!watermarkService.resetForUidValidityChange(
                            watermark.getId(), leaseVersion, currentValidity)) {
                        log.warn("Lease superseded during UIDVALIDITY reset for config={} folder={} "
                                + "— abandoning this fetch", config.getId(), folderName);
                        return 0;
                    }
                    leaseVersion++;
                    startUid = 1L;
                } else {
                    long lastSeen = watermark.getLastSeenUid() == null
                            ? 0L : watermark.getLastSeenUid();
                    startUid = lastSeen + 1;
                }

                Message[] all = uidFolder.getMessagesByUID(startUid, UIDFolder.LASTUID);
                int batchLimit = messageProperties.getMail().getFetch().getBatchLimit();
                int sliceLength = Math.min(all.length, batchLimit);
                if (sliceLength > 0) {
                    Message[] slice = Arrays.copyOfRange(all, 0, sliceLength);
                    FetchProfile fp = new FetchProfile();
                    fp.add(FetchProfile.Item.ENVELOPE);
                    fp.add(UIDFolder.FetchProfileItem.UID);
                    folder.fetch(slice, fp);

                    List<MailReceiveRecord> classified = new ArrayList<>();
                    for (Message message : slice) {
                        long uid = uidFolder.getUID(message);
                        try {
                            MailReceiveRecord record = processOne(message, config, folderName);
                            if (record != null) {
                                fetched++;
                                if (StringUtils.hasText(record.getOriginalMessageId())) {
                                    classified.add(record);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to process UID {} in folder '{}': {}",
                                    uid, folderName, e.getMessage(), e);
                            // Advance past poison UIDs so they don't block future batches.
                            // Message-ID dedup catches any double-processing on retry.
                        }
                        if (maxUidProcessed == null || uid > maxUidProcessed) {
                            maxUidProcessed = uid;
                        }
                    }
                    if (!classified.isEmpty()) {
                        bounceReceiptLinker.link(classified);
                    }
                }
            } finally {
                folder.close(false);
            }
            watermarkService.releaseSuccess(watermark.getId(), leaseVersion, maxUidProcessed);
        } catch (MessagingException e) {
            log.error("Failed to fetch from folder '{}' on [{}]: {}",
                    folderName, config.getHost(), e.getMessage());
            watermarkService.releaseFailure(watermark.getId(), leaseVersion, e.getMessage());
        } catch (RuntimeException e) {
            watermarkService.releaseFailure(watermark.getId(), leaseVersion, e.getMessage());
            throw e;
        }
        return fetched;
    }

    /**
     * POP3 path: protocol semantics are destructive — every successfully
     * processed message is marked DELETED and removed from the server on
     * folder close. POP3 has no UID/flag concept comparable to IMAP, so the
     * watermark / lease mechanism does not apply. Concurrent workers are
     * naturally bounded by Pulsar message dispatch and Message-ID dedup.
     */
    private int fetchFromPop3Folder(Store store, String folderName, MailReceiveServerConfig config) {
        int fetched = 0;
        try {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                log.debug("Folder '{}' does not exist on server [{}], skipping.",
                        folderName, config.getHost());
                return 0;
            }
            folder.open(Folder.READ_WRITE);
            try {
                int batchLimit = messageProperties.getMail().getFetch().getBatchLimit();
                Message[] messages = folder.getMessages();
                List<MailReceiveRecord> classified = new ArrayList<>();
                int processed = 0;
                for (Message message : messages) {
                    if (processed >= batchLimit) break;
                    processed++;
                    try {
                        MailReceiveRecord record = processOne(message, config, folderName);
                        if (record != null) {
                            fetched++;
                            if (StringUtils.hasText(record.getOriginalMessageId())) {
                                classified.add(record);
                            }
                        }
                        // POP3 protocol = drain. Mark DELETED on success — including
                        // BodyTooLarge envelope-only persistence — so oversized mails
                        // don't get re-fetched forever. Expunge happens on close(true).
                        message.setFlag(Flags.Flag.DELETED, true);
                    } catch (Exception e) {
                        log.warn("Failed to process POP3 message in folder '{}': {} — leaving on server for retry",
                                folderName, e.getMessage(), e);
                    }
                }
                if (!classified.isEmpty()) {
                    bounceReceiptLinker.link(classified);
                }
            } finally {
                folder.close(true);   // expunge on close → DELETED messages removed
            }
        } catch (MessagingException e) {
            log.warn("Failed to fetch from POP3 folder '{}' on [{}]: {}",
                    folderName, config.getHost(), e.getMessage());
        }
        return fetched;
    }

    /**
     * Parse the folder list from {@code fetchFolders} (comma-separated).
     * Falls back to {@code "INBOX"} if unset, blank, or contains only empty entries.
     */
    private List<String> parseFolders(MailReceiveServerConfig config) {
        if (StringUtils.hasText(config.getFetchFolders())) {
            List<String> folders = Arrays.stream(config.getFetchFolders().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (!folders.isEmpty()) {
                return folders;
            }
        }
        return List.of("INBOX");
    }

    /**
     * Single-message pipeline shared by IMAP and POP3 paths: parse the message
     * into a record (via {@link MailMessageParser}), skip duplicates, archive
     * EML when enabled, and persist.
     *
     * @return the persisted record, or {@code null} when this message was a
     *         duplicate and was skipped.
     */
    private MailReceiveRecord processOne(Message message, MailReceiveServerConfig config, String folderName)
            throws MessagingException, IOException {
        MailReceiveRecord record = mailMessageParser.parse(message, config, folderName);
        if (isDuplicate(record.getMessageId(), config.getId())) {
            return null;
        }
        // Don't archive truncated records (oversized / MIME-limit) — there's no
        // faithful body to keep. archiveEml is itself a no-op when disabled.
        if (record.getTruncationReason() == null) {
            mailMessageParser.archiveEml(message, record);
        }
        recordService.createOne(record);
        return record;
    }

    private boolean isDuplicate(String messageId, Long serverConfigId) {
        if (!StringUtils.hasText(messageId)) return false;
        Filters filters = new Filters()
                .eq(MailReceiveRecord::getMessageId, messageId)
                .eq(MailReceiveRecord::getServerConfigId, serverConfigId);
        return recordService.count(filters) > 0;
    }

    private Properties buildSessionProperties(MailReceiveServerConfig config, String protocol) {
        Properties props = new Properties();
        props.put("mail." + protocol + ".host", config.getHost());
        props.put("mail." + protocol + ".port", config.getPort());
        props.put("mail." + protocol + ".ssl.enable", Boolean.TRUE.equals(config.getSslEnabled()));
        // Global transport timeouts; per-config tunability removed (see
        // MessageProperties.Transport Javadoc).
        long connTimeoutMs = messageProperties.getMail().getTransport()
                .getConnectionTimeout().toMillis();
        long readTimeoutMs = messageProperties.getMail().getTransport()
                .getReadTimeout().toMillis();
        props.put("mail." + protocol + ".connectiontimeout", connTimeoutMs);
        props.put("mail." + protocol + ".timeout", readTimeoutMs);
        return props;
    }
}
