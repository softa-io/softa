package io.softa.starter.message.mail.service;

import java.util.List;

/**
 * Public interface for fetching and managing incoming emails.
 * <p>
 * The implementation polls the configured IMAP/POP3 server for the current tenant
 * (or falls back to the platform-level server) and persists new messages as
 * {@link io.softa.starter.message.mail.entity.MailReceiveRecord} rows.
 */
public interface MailReceiveService {

    /**
     * Fetch new emails from the resolved receiving server for the current tenant.
     *
     * @return number of new emails fetched and persisted
     */
    int fetchNewMails();

    /**
     * Fetch new emails from a specific server config.
     *
     * @param serverConfigId mail_server_config ID with serverRole = RECEIVING
     * @return number of new emails fetched and persisted
     */
    int fetchNewMails(Long serverConfigId);

    /**
     * Mark a single received mail as read.
     */
    void markAsRead(Long receiveRecordId);

    /**
     * Mark multiple received mails as read.
     */
    void markAsRead(List<Long> receiveRecordIds);
}
