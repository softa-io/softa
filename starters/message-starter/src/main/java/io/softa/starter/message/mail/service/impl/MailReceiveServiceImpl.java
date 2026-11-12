package io.softa.starter.message.mail.service.impl;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import jakarta.mail.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.EntityService;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.AbstractMailFetchWatermark;
import io.softa.starter.message.mail.entity.AbstractMailReceiveRecord;
import io.softa.starter.message.mail.entity.AbstractMailReceiveServer;
import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.entity.PlatformMailReceiveRecord;
import io.softa.starter.message.mail.enums.*;
import io.softa.starter.message.mail.service.MailFetchImapWatermarkService;
import io.softa.starter.message.mail.service.MailFetchWatermarkService;
import io.softa.starter.message.mail.service.MailReceiveRecordService;
import io.softa.starter.message.mail.service.MailReceiveService;
import io.softa.starter.message.mail.service.PlatformMailFetchWatermarkService;
import io.softa.starter.message.mail.service.PlatformMailReceiveRecordService;
import io.softa.starter.message.mail.support.BounceReceiptLinker;
import io.softa.starter.message.mail.support.MailMessageParser;
import io.softa.starter.message.mail.support.MailServerDispatcher;
import io.softa.starter.message.mail.support.MailSessionSettings;
import io.softa.starter.message.mail.support.TenantMailHostGuard;

/**
 * Implementation of {@link MailReceiveService}.
 * <p>
 * Thin protocol-fetch orchestrator: connects to the resolved IMAP/POP3 server,
 * pulls unseen messages, and for each one delegates the heavy lifting to two
 * collaborators — {@link MailMessageParser} (MIME → {@link MailReceiveRecord})
 * and {@link BounceReceiptLinker} (read-receipt / bounce → originating
 * {@code MailSendRecord}). This class owns only the connect / per-folder fetch
 * lifecycle (IMAP watermark+lease, POP3 destructive drain), dedup, and persist.
 * <p>
 * One flow serves both owners; a {@link Tier} binds where an owner's mail and watermarks are stored
 * and whose send records its bounces may mark. A tenant's receive server is checked against hosts
 * inside the deployment before connecting.
 */
@Slf4j
@Service
public class MailReceiveServiceImpl implements MailReceiveService {

    /**
     * Messages fetched and persisted per scheduled run per (config, folder). Listing the folder is
     * cheap; this caps how many bodies one run fetches. The rest wait for the next run.
     */
    static final int BATCH_LIMIT = 100;

    /**
     * An IMAP fetch lease held longer than this is abandoned (its worker most likely died) and another
     * worker may take it over. Conservative: a legitimate fetch finishes well within it.
     */
    static final Duration LEASE_TIMEOUT = Duration.ofHours(1);

    @Autowired
    private MailServerDispatcher dispatcher;

    @Autowired
    private MailReceiveRecordService recordService;

    @Autowired
    private PlatformMailReceiveRecordService platformRecordService;

    @Autowired
    private MailMessageParser mailMessageParser;

    @Autowired
    private BounceReceiptLinker bounceReceiptLinker;

    @Autowired
    private MailFetchImapWatermarkService watermarkService;

    @Autowired
    private PlatformMailFetchWatermarkService platformWatermarkService;

    @Autowired
    private TenantMailHostGuard hostGuard;

    /** Where one owner's received mail and watermarks live, and whose send records its bounces mark. */
    private record Tier<R extends AbstractMailReceiveRecord>(
            MailServerOwner owner,
            EntityService<R, Long> records,
            Supplier<R> newRecord,
            MailFetchWatermarkService<? extends AbstractMailFetchWatermark> watermarks) {}

    private Tier<MailReceiveRecord> tenantTier() {
        return new Tier<>(MailServerOwner.TENANT, recordService, MailReceiveRecord::new, watermarkService);
    }

    private Tier<PlatformMailReceiveRecord> platformTier() {
        return new Tier<>(MailServerOwner.PLATFORM, platformRecordService, PlatformMailReceiveRecord::new,
                platformWatermarkService);
    }

    @Autowired
    private MessageProperties messageProperties;

    @Override
    public int fetchNewMails() {
        return fetchFromServer(dispatcher.resolveReceive(), tenantTier());
    }

    @Override
    public int fetchNewMails(Long serverConfigId) {
        return fetchFromServer(dispatcher.resolveReceiveById(serverConfigId), tenantTier());
    }

    @Override
    public int fetchPlatformMails() {
        return fetchFromServer(dispatcher.resolvePlatformReceive(), platformTier());
    }

    @Override
    public int fetchPlatformMails(Long serverConfigId) {
        return fetchFromServer(dispatcher.resolvePlatformReceiveById(serverConfigId), platformTier());
    }

    @Override
    public void markAsRead(Long receiveRecordId) {
        markRead(tenantTier(), List.of(receiveRecordId), true);
    }

    @Override
    public void markAsRead(List<Long> receiveRecordIds) {
        markRead(tenantTier(), receiveRecordIds, false);
    }

    @Override
    public void markPlatformAsRead(Long receiveRecordId) {
        markRead(platformTier(), List.of(receiveRecordId), true);
    }

    @Override
    public void markPlatformAsRead(List<Long> receiveRecordIds) {
        markRead(platformTier(), receiveRecordIds, false);
    }

    private <R extends AbstractMailReceiveRecord> void markRead(Tier<R> tier, List<Long> ids, boolean mustExist) {
        if (mustExist && tier.records().getById(ids.getFirst()).isEmpty()) {
            throw new IllegalArgumentException("Received mail {0} not found.", ids.getFirst());
        }
        R patch = tier.newRecord().get();
        patch.setStatus(MailReceiveStatus.READ);
        tier.records().updateByFilter(new Filters().in(ModelConstant.ID, ids), patch);
    }

    // -------------------------------------------------
    // Fetch Logic
    // -------------------------------------------------

    private <R extends AbstractMailReceiveRecord> int fetchFromServer(AbstractMailReceiveServer config, Tier<R> tier) {
        if (tier.owner() == MailServerOwner.TENANT) {
            hostGuard.requirePublicBeforeConnecting(config.getHost());
        }
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
                    totalFetched += fetchFromFolder(store, folderName, config, tier);
                }
            }
        } catch (MessagingException e) {
            throw new BusinessException("Failed to fetch mails from {0}:{1} — {2}",
                    config.getHost(), config.getPort(), e.getMessage(), e);
        }

        log.info("Fetched {} new email(s) from [{}:{}]", totalFetched, config.getHost(), config.getPort());
        return totalFetched;
    }

    private <R extends AbstractMailReceiveRecord> int fetchFromFolder(Store store, String folderName,
                                                                       AbstractMailReceiveServer config, Tier<R> tier) {
        if (config.getProtocol().isImap()) {
            return fetchFromImapFolder(store, folderName, config, tier);
        }
        return fetchFromPop3Folder(store, folderName, config, tier);
    }

    /**
     * IMAP path: non-destructive incremental fetch driven by per-(config, folder)
     * UID watermark. Acquires a soft lease before fetching to prevent two workers
     * from processing the same (config, folder) under the Shared Pulsar
     * subscription. UIDVALIDITY change resets the watermark to 0 — duplicate
     * Message-IDs are still caught by {@link #isDuplicate}.
     */
    private <R extends AbstractMailReceiveRecord> int fetchFromImapFolder(Store store, String folderName,
                                                                           AbstractMailReceiveServer config,
                                                                           Tier<R> tier) {
        MailFetchWatermarkService<? extends AbstractMailFetchWatermark> watermarks = tier.watermarks();
        Optional<? extends AbstractMailFetchWatermark> leaseOpt = watermarks.tryAcquireLease(
                config.getId(), folderName,
                LEASE_TIMEOUT);
        if (leaseOpt.isEmpty()) {
            return 0;
        }
        AbstractMailFetchWatermark watermark = leaseOpt.get();
        // Lease token: each successful CAS transition we perform bumps it by one.
        long leaseVersion = watermark.getVersion() != null ? watermark.getVersion() : 0L;
        int fetched = 0;
        Long maxUidProcessed = null;

        try {
            Folder folder = store.getFolder(folderName);
            if (!folder.exists()) {
                log.debug("Folder '{}' does not exist on server [{}], skipping.",
                        folderName, config.getHost());
                watermarks.releaseSuccess(watermark.getId(), leaseVersion, null);
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
                    if (!watermarks.resetForUidValidityChange(
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
                int batchLimit = BATCH_LIMIT;
                int sliceLength = Math.min(all.length, batchLimit);
                if (sliceLength > 0) {
                    Message[] slice = Arrays.copyOfRange(all, 0, sliceLength);
                    FetchProfile fp = new FetchProfile();
                    fp.add(FetchProfile.Item.ENVELOPE);
                    fp.add(UIDFolder.FetchProfileItem.UID);
                    folder.fetch(slice, fp);

                    List<R> classified = new ArrayList<>();
                    for (Message message : slice) {
                        long uid = uidFolder.getUID(message);
                        try {
                            R record = processOne(message, config, folderName, tier);
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
                        bounceReceiptLinker.link(classified, tier.owner());
                    }
                }
            } finally {
                folder.close(false);
            }
            watermarks.releaseSuccess(watermark.getId(), leaseVersion, maxUidProcessed);
        } catch (MessagingException e) {
            log.error("Failed to fetch from folder '{}' on [{}]: {}",
                    folderName, config.getHost(), e.getMessage());
            watermarks.releaseFailure(watermark.getId(), leaseVersion, e.getMessage());
        } catch (RuntimeException e) {
            watermarks.releaseFailure(watermark.getId(), leaseVersion, e.getMessage());
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
    private <R extends AbstractMailReceiveRecord> int fetchFromPop3Folder(Store store, String folderName,
                                                                           AbstractMailReceiveServer config,
                                                                           Tier<R> tier) {
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
                int batchLimit = BATCH_LIMIT;
                Message[] messages = folder.getMessages();
                List<R> classified = new ArrayList<>();
                int processed = 0;
                for (Message message : messages) {
                    if (processed >= batchLimit) break;
                    processed++;
                    try {
                        R record = processOne(message, config, folderName, tier);
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
                    bounceReceiptLinker.link(classified, tier.owner());
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
    private List<String> parseFolders(AbstractMailReceiveServer config) {
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
    private <R extends AbstractMailReceiveRecord> R processOne(Message message, AbstractMailReceiveServer config,
                                                                String folderName, Tier<R> tier)
            throws MessagingException, IOException {
        R record = mailMessageParser.parse(message, config, folderName, tier.newRecord());
        if (isDuplicate(record.getMessageId(), config.getId(), tier)) {
            return null;
        }
        tier.records().createOne(record);
        return record;
    }

    private boolean isDuplicate(String messageId, Long serverConfigId, Tier<?> tier) {
        if (!StringUtils.hasText(messageId)) return false;
        Filters filters = new Filters()
                .eq(AbstractMailReceiveRecord::getMessageId, messageId)
                .eq(AbstractMailReceiveRecord::getServerConfigId, serverConfigId);
        return tier.records().count(filters) > 0;
    }

    private Properties buildSessionProperties(AbstractMailReceiveServer config, String protocol) {
        Properties props = new Properties();
        props.put("mail." + protocol + ".host", config.getHost());
        props.put("mail." + protocol + ".port", config.getPort());
        props.put("mail." + protocol + ".ssl.enable", Boolean.TRUE.equals(config.getSslEnabled()));
        // Global transport timeouts (see the MessageProperties.Transport Javadoc) and the debug log.
        MailSessionSettings.apply(props, protocol, messageProperties.getMail(), config.getId());
        return props;
    }
}
