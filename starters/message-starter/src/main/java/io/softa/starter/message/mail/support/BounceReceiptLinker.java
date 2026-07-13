package io.softa.starter.message.mail.support;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.ReceivedMailType;
import io.softa.starter.message.mail.service.MailSendRecordService;

/**
 * Links classified inbound mail (read-receipts / bounces) back to the
 * originating {@link MailSendRecord} via CAS updates.
 * <p>
 * Extracted from {@code MailReceiveServiceImpl}: this is the only writer of
 * send records on the receive path — a concern orthogonal to fetching mail.
 */
@Slf4j
@Component
public class BounceReceiptLinker {

    @Autowired
    private MailSendRecordService sendRecordService;

    /**
     * Bulk linkage: look up all referenced send records in one {@code IN()}
     * query, then apply bounce / read-receipt updates.
     */
    public void link(List<MailReceiveRecord> receiveRecords) {
        List<String> ids = receiveRecords.stream()
                .map(MailReceiveRecord::getOriginalMessageId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (ids.isEmpty()) return;
        Map<String, MailSendRecord> sendIndex = sendRecordService.findByMessageIds(ids);
        for (MailReceiveRecord receive : receiveRecords) {
            MailSendRecord send = sendIndex.get(receive.getOriginalMessageId());
            if (send == null) continue;
            ReceivedMailType mailType = receive.getMailType();
            if (mailType == ReceivedMailType.READ_RECEIPT) {
                applyReadReceipt(send, receive.getOriginalMessageId());
            } else if (mailType == ReceivedMailType.BOUNCE) {
                applyBounce(send, receive);
            }
        }
    }

    private void applyReadReceipt(MailSendRecord sendRecord, String originalMessageId) {
        long v = sendRecord.getVersion() != null ? sendRecord.getVersion() : 0L;
        if (sendRecordService.markReadReceiptReceived(sendRecord.getId(), v)) {
            log.info("Read receipt received for send record id={}, Message-ID={}",
                    sendRecord.getId(), originalMessageId);
        } else {
            log.debug("Read-receipt CAS miss for send record id={} v={} — concurrent update, skipping",
                    sendRecord.getId(), v);
        }
    }

    private void applyBounce(MailSendRecord sendRecord, MailReceiveRecord receiveRecord) {
        String bounceCode = StringUtils.hasText(receiveRecord.getSmtpReplyCode())
                ? receiveRecord.getSmtpReplyCode() : "";
        if (StringUtils.hasText(receiveRecord.getEnhancedStatusCode())) {
            bounceCode = bounceCode.isEmpty()
                    ? receiveRecord.getEnhancedStatusCode()
                    : bounceCode + " " + receiveRecord.getEnhancedStatusCode();
        }
        String codeToStore = StringUtils.hasText(bounceCode) ? bounceCode.trim() : null;
        long v = sendRecord.getVersion() != null ? sendRecord.getVersion() : 0L;
        if (sendRecordService.markBounced(sendRecord.getId(), v, codeToStore)) {
            log.info("Bounce detected for send record id={}, code={}, Message-ID={}",
                    sendRecord.getId(), codeToStore, receiveRecord.getOriginalMessageId());
        } else {
            log.debug("Bounce CAS miss for send record id={} v={} — concurrent update, skipping",
                    sendRecord.getId(), v);
        }
    }
}
