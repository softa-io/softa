package io.softa.starter.message.mail.support;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.message.mail.entity.MailReceiveRecord;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.ReceivedMailType;
import io.softa.starter.message.mail.service.MailSendRecordService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BounceReceiptLinkerTest {

    private BounceReceiptLinker linker;
    private MailSendRecordService sendRecordService;

    @BeforeEach
    void setUp() {
        linker = new BounceReceiptLinker();
        sendRecordService = mock(MailSendRecordService.class);
        ReflectionTestUtils.setField(linker, "sendRecordService", sendRecordService);
    }

    @Test
    void skipsNormalMailWithoutLookup() {
        MailReceiveRecord record = new MailReceiveRecord();
        record.setMailType(ReceivedMailType.NORMAL);
        record.setOriginalMessageId(null);

        linker.link(List.of(record));
        // No originalMessageId → short-circuits before findByMessageIds.
        verify(sendRecordService, never()).findByMessageIds(any());
    }

    @Test
    void skipsWhenNoOriginalMessageId() {
        MailReceiveRecord record = new MailReceiveRecord();
        record.setMailType(ReceivedMailType.BOUNCE);
        record.setOriginalMessageId(null);

        linker.link(List.of(record));
        verify(sendRecordService, never()).findByMessageIds(any());
    }

    @Test
    void appliesReadReceiptCas() {
        MailSendRecord send = new MailSendRecord();
        send.setId(5L);
        send.setVersion(2L);
        when(sendRecordService.findByMessageIds(any())).thenReturn(Map.of("<m@x>", send));
        when(sendRecordService.markReadReceiptReceived(5L, 2L)).thenReturn(true);

        MailReceiveRecord receive = new MailReceiveRecord();
        receive.setMailType(ReceivedMailType.READ_RECEIPT);
        receive.setOriginalMessageId("<m@x>");

        linker.link(List.of(receive));

        verify(sendRecordService).markReadReceiptReceived(5L, 2L);
    }

    @Test
    void appliesBounceCasWithCombinedCode() {
        MailSendRecord send = new MailSendRecord();
        send.setId(6L);
        send.setVersion(0L);
        when(sendRecordService.findByMessageIds(any())).thenReturn(Map.of("<b@x>", send));
        when(sendRecordService.markBounced(eq(6L), anyLong(), any())).thenReturn(true);

        MailReceiveRecord receive = new MailReceiveRecord();
        receive.setMailType(ReceivedMailType.BOUNCE);
        receive.setOriginalMessageId("<b@x>");
        receive.setSmtpReplyCode("550");
        receive.setEnhancedStatusCode("5.1.1");

        linker.link(List.of(receive));

        // SMTP reply code + enhanced status code are combined into one stored value.
        verify(sendRecordService).markBounced(6L, 0L, "550 5.1.1");
    }
}
