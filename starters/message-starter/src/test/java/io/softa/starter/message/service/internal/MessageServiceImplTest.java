package io.softa.starter.message.service.internal;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.inbox.dto.SendInboxDTO;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.sms.dto.SendSmsDTO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageServiceImplTest {

    private MailMessageHandler mailHandler;
    private SmsMessageHandler smsHandler;
    private InboxMessageHandler inboxHandler;
    private MessageServiceImpl service;

    @BeforeEach
    void setUp() {
        mailHandler = mock(MailMessageHandler.class);
        smsHandler = mock(SmsMessageHandler.class);
        inboxHandler = mock(InboxMessageHandler.class);
        service = new MessageServiceImpl(mailHandler, smsHandler, inboxHandler);
    }

    @Test
    void singleMethodsDelegateToTheirChannelHandler() {
        SendMailDTO mail = new SendMailDTO();
        SendSmsDTO sms = new SendSmsDTO();
        SendInboxDTO inbox = new SendInboxDTO();
        when(mailHandler.send(mail)).thenReturn(1L);
        when(smsHandler.send(sms)).thenReturn(2L);
        when(inboxHandler.send(inbox)).thenReturn(3L);

        assertEquals(1L, service.sendMail(mail));
        assertEquals(2L, service.sendSms(sms));
        assertEquals(3L, service.sendInbox(inbox));
    }

    @Test
    void mailBatchPreservesInputOrderInReturnedIds() {
        SendMailDTO first = new SendMailDTO();
        first.setSubject("first");
        SendMailDTO second = new SendMailDTO();
        second.setSubject("second");
        when(mailHandler.send(first)).thenReturn(11L);
        when(mailHandler.send(second)).thenReturn(12L);

        assertEquals(List.of(11L, 12L), service.sendMailBatch(List.of(first, second)));
        verify(mailHandler).send(first);
        verify(mailHandler).send(second);
    }

    @Test
    void smsBatchPreservesInputOrderInReturnedIds() {
        SendSmsDTO first = new SendSmsDTO();
        first.setPhoneNumber("+111");
        SendSmsDTO second = new SendSmsDTO();
        second.setPhoneNumber("+222");
        when(smsHandler.send(first)).thenReturn(21L);
        when(smsHandler.send(second)).thenReturn(22L);

        assertEquals(List.of(21L, 22L), service.sendSmsBatch(List.of(first, second)));
    }

    @Test
    void inboxBatchUsesOneBulkPersistenceCall() {
        List<SendInboxDTO> messages = List.of(new SendInboxDTO(), new SendInboxDTO());
        when(inboxHandler.sendBatch(messages)).thenReturn(List.of(31L, 32L));

        assertEquals(List.of(31L, 32L), service.sendInboxBatch(messages));
        verify(inboxHandler).sendBatch(messages);
    }

    @Test
    void nullSingleMessageIsRejectedBeforeDelegation() {
        assertThrows(BusinessException.class, () -> service.sendMail(null));
        assertThrows(BusinessException.class, () -> service.sendSms(null));
        assertThrows(BusinessException.class, () -> service.sendInbox(null));
        verifyNoInteractions(mailHandler, smsHandler, inboxHandler);
    }

    @Test
    void nullOrEmptyBatchIsRejectedBeforeDelegation() {
        assertThrows(BusinessException.class, () -> service.sendMailBatch(null));
        assertThrows(BusinessException.class, () -> service.sendSmsBatch(List.of()));
        assertThrows(BusinessException.class, () -> service.sendInboxBatch(List.of()));
        verifyNoInteractions(mailHandler, smsHandler, inboxHandler);
    }

    @Test
    void oversizedBatchIsRejectedBeforeDelegation() {
        List<SendMailDTO> messages = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            messages.add(new SendMailDTO());
        }

        assertThrows(BusinessException.class, () -> service.sendMailBatch(messages));
        verifyNoInteractions(mailHandler);
    }

    @Test
    void nullBatchItemIsRejectedBeforeAnyItemIsSent() {
        List<SendSmsDTO> messages = new ArrayList<>();
        messages.add(new SendSmsDTO());
        messages.add(null);

        assertThrows(BusinessException.class, () -> service.sendSmsBatch(messages));
        verifyNoInteractions(smsHandler);
    }
}
