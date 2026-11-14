package io.softa.starter.message.shared.maintenance;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.starter.message.config.MessageProperties;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.smtp.SmtpMailTransport;
import io.softa.starter.message.shared.ErrorCategory;
import io.softa.starter.message.shared.retry.SendFailureHandler;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The sweeper revives a stuck SENDING record while it has retries left, and
 * dead-letters it once the budget is spent — an attempt that never finishes
 * never reaches the failure handler that otherwise enforces the budget.
 */
class ZombieRecordSweeperTest {

    private MailSendRecordService mailService;
    private SmsSendRecordService smsService;
    private SendFailureHandler sendFailureHandler;
    private ZombieRecordSweeper sweeper;

    @BeforeEach
    void setUp() {
        mailService = mock(MailSendRecordService.class);
        smsService = mock(SmsSendRecordService.class);
        sendFailureHandler = mock(SendFailureHandler.class);

        sweeper = sweeperWith(new MessageProperties());

        when(mailService.searchList(any(FlexQuery.class))).thenReturn(List.of());
        when(smsService.searchList(any(FlexQuery.class))).thenReturn(List.of());
    }

    private ZombieRecordSweeper sweeperWith(MessageProperties properties) {
        return new ZombieRecordSweeper(mailService, smsService, sendFailureHandler, properties);
    }

    private static MailSendRecord stuckMail(int retryCount) {
        MailSendRecord r = new MailSendRecord();
        r.setId(5L);
        r.setTenantId(42L);
        r.setStatus(MailSendStatus.SENDING);
        r.setVersion(3L);
        r.setRetryCount(retryCount);
        return r;
    }

    @Test
    void aStuckRecordWithRetriesLeftIsRevivedAndDueAtOnce() {
        when(mailService.searchList(any(FlexQuery.class))).thenReturn(List.of(stuckMail(4)));
        when(mailService.markRetry(eq(5L), eq(3L), eq("ZOMBIE_RECOVERED"), any(), any(LocalDateTime.class)))
                .thenReturn(true);
        LocalDateTime before = LocalDateTime.now();

        sweeper.sweep();

        // RETRY with nextRetryAt = now: due for the delivery loop's next poll.
        verify(mailService).markRetry(eq(5L), eq(3L), eq("ZOMBIE_RECOVERED"), any(),
                argThat(at -> !at.isBefore(before) && !at.isAfter(LocalDateTime.now())));
        verifyNoInteractions(sendFailureHandler);
    }

    @Test
    void aStuckRecordWithTheBudgetSpentIsDeadLetteredNotRevived() {
        // default-max-attempts = 5: five retries already made, this attempt is stuck again.
        when(mailService.searchList(any(FlexQuery.class))).thenReturn(List.of(stuckMail(5)));

        sweeper.sweep();

        verify(sendFailureHandler).deadLetter(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(3L), eq(5),
                eq("ZOMBIE_EXHAUSTED"), any(), eq(ErrorCategory.UNKNOWN), any());
        verify(mailService, never()).markRetry(any(), anyLong(), any(), any(), any());
    }

    @Test
    void smsFollowsTheSameBudget() {
        SmsSendRecord sms = new SmsSendRecord();
        sms.setId(9L);
        sms.setTenantId(42L);
        sms.setStatus(SmsSendStatus.SENDING);
        sms.setVersion(1L);
        sms.setRetryCount(5);
        sms.setProviderType(SmsProvider.TWILIO);
        when(smsService.searchList(any(FlexQuery.class))).thenReturn(List.of(sms));

        sweeper.sweep();

        verify(sendFailureHandler).deadLetter(eq("sms"), eq("twilio"), eq(9L), eq(1L), eq(5),
                eq("ZOMBIE_EXHAUSTED"), any(), eq(ErrorCategory.UNKNOWN), any());
        verify(smsService, never()).markRetry(any(), anyLong(), any(), any(), any());
    }

    @Test
    void theDefaultTimeoutsFitTheStaleWindow() {
        // 5s connect + 30s read on both channels: well inside the five minutes.
        assertDoesNotThrow(() -> sweeperWith(new MessageProperties()));
    }

    @Test
    void startupRefusesTimeoutsASlowSendCouldOutlastTheWindowWith() {
        // 5s + 3min: a send that is only slow could still be SENDING when the sweep takes it for a crashed one.
        MessageProperties properties = new MessageProperties();
        properties.getMail().getTransport().setReadTimeout(Duration.ofMinutes(3));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> sweeperWith(properties));
        assertTrue(e.getMessage().contains("softa.message.mail.transport"), e.getMessage());
    }

    @Test
    void halfTheWindowIsTheLimitOnEachChannel() {
        MessageProperties properties = new MessageProperties();
        properties.getSms().getTransport().setConnectionTimeout(Duration.ofSeconds(30));
        properties.getSms().getTransport().setReadTimeout(Duration.ofMinutes(2));
        assertDoesNotThrow(() -> sweeperWith(properties));   // exactly 2m30s

        properties.getSms().getTransport().setReadTimeout(Duration.ofMinutes(2).plusSeconds(1));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> sweeperWith(properties));
        assertTrue(e.getMessage().contains("softa.message.sms.transport"), e.getMessage());
    }

    @Test
    void aZeroTimeoutIsNoTimeoutAndIsRefused() {
        MessageProperties properties = new MessageProperties();
        properties.getMail().getTransport().setReadTimeout(Duration.ZERO);

        assertThrows(IllegalStateException.class, () -> sweeperWith(properties));
    }
}
