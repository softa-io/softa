package io.softa.starter.message.mail.service;

import java.util.List;

/**
 * Public interface for fetching and managing incoming emails.
 * <p>
 * The implementation polls an IMAP / POP3 server and stores what arrives as the server owner's mail:
 * a tenant's receive server fills that tenant's
 * {@link io.softa.starter.message.mail.entity.MailReceiveRecord}s, the platform's fills
 * {@link io.softa.starter.message.mail.entity.PlatformMailReceiveRecord}s. Neither falls back to the
 * other: a tenant without a receive server of its own has nothing to fetch.
 */
public interface MailReceiveService {

    /**
     * Fetch new emails from the current tenant's default receive server.
     *
     * @return number of new emails fetched and persisted
     * @throws io.softa.framework.base.exception.BusinessException when the tenant has no enabled
     *         default receive server
     */
    int fetchNewMails();

    /**
     * Fetch new emails from one of the current tenant's receive servers; another owner's id is not
     * found.
     *
     * @return number of new emails fetched and persisted
     */
    int fetchNewMails(Long serverConfigId);

    /** Fetch new emails from the platform's default receive server. */
    int fetchPlatformMails();

    /** Fetch new emails from one of the platform's receive servers. */
    int fetchPlatformMails(Long serverConfigId);

    /** Mark one of the tenant's received mails as read. */
    void markAsRead(Long receiveRecordId);

    /** Mark several of the tenant's received mails as read. */
    void markAsRead(List<Long> receiveRecordIds);

    /** Mark one of the platform's received mails as read. */
    void markPlatformAsRead(Long receiveRecordId);

    /** Mark several of the platform's received mails as read. */
    void markPlatformAsRead(List<Long> receiveRecordIds);
}
