package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.MailTemplateService;
import io.softa.starter.message.mail.smtp.SmtpMailRequest;
import io.softa.starter.message.mail.smtp.SmtpMailTransport;
import io.softa.starter.message.mail.smtp.SmtpSendResult;
import io.softa.starter.message.mail.support.MailServerDispatcher;
import io.softa.starter.message.shared.MonthlyQuotaGuard;
import io.softa.starter.message.shared.RateLimiter;
import io.softa.starter.message.shared.TenantScopes;
import io.softa.starter.message.shared.metrics.MessageMetrics;
import io.softa.starter.message.shared.retry.SendFailureHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Delivery contract for {@link MailDeliveryProcessor#deliver}: a claimed record is prepared once —
 * template rendered, server chosen, quota charged, written back — then rate-limited, sent and marked;
 * anything that stops preparation lands on the record as a failure with a marker code. Collaborators
 * (record CAS, SMTP transport, rate limiter, failure handler) are mocked.
 */
class MailDeliveryProcessorTest {

    private MailServerDispatcher dispatcher;
    private MailTemplateService templateService;
    private MonthlyQuotaGuard quotaGuard;
    private SmtpMailTransport smtpMailTransport;
    private MailSendRecordService recordService;
    private RateLimiter rateLimiter;
    private SendFailureHandler sendFailureHandler;
    private MessageMetrics metrics;
    private MailDeliveryProcessor processor;

    @BeforeEach
    void setUp() {
        dispatcher = mock(MailServerDispatcher.class);
        templateService = mock(MailTemplateService.class);
        quotaGuard = mock(MonthlyQuotaGuard.class);
        smtpMailTransport = mock(SmtpMailTransport.class);
        recordService = mock(MailSendRecordService.class);
        rateLimiter = mock(RateLimiter.class);
        sendFailureHandler = mock(SendFailureHandler.class);
        metrics = mock(MessageMetrics.class);

        processor = new MailDeliveryProcessor();
        ReflectionTestUtils.setField(processor, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(processor, "templateService", templateService);
        ReflectionTestUtils.setField(processor, "quotaGuard", quotaGuard);
        ReflectionTestUtils.setField(processor, "smtpMailTransport", smtpMailTransport);
        ReflectionTestUtils.setField(processor, "recordService", recordService);
        ReflectionTestUtils.setField(processor, "rateLimiter", rateLimiter);
        ReflectionTestUtils.setField(processor, "sendFailureHandler", sendFailureHandler);
        ReflectionTestUtils.setField(processor, "metrics", metrics);

        when(rateLimiter.tryAcquire(eq("mail"), anyLong(), any(), any())).thenReturn(RateLimiter.Outcome.ALLOWED);
        when(recordService.markPrepared(any(MailSendRecord.class), anyLong())).thenReturn(true);
    }

    // ---- a prepared record: replayed as it is -----------------------------------------------------

    /** Prepared on an earlier attempt: content, server and sender are fixed. */
    private static MailSendRecord prepared() {
        MailSendRecord r = new MailSendRecord();
        r.setId(5L);
        r.setStatus(MailSendStatus.SENDING);
        r.setRetryCount(0);
        r.setServerConfigId(10L);
        r.setFromAddress("noreply@example.com");
        r.setToAddresses(List.of("alice@example.com"));
        r.setSubject("Hi");
        r.setBodyText("Body");
        r.setBodyMode(BodyMode.PLAIN);
        return r;
    }

    private static MailSendServerConfig config(long id) {
        MailSendServerConfig c = new MailSendServerConfig();
        c.setId(id);
        c.setFromAddress("noreply@example.com");
        return c;
    }

    @Test
    void aPreparedRecordIsSentWithoutBeingPreparedAgain() {
        when(dispatcher.resolveSendById(10L)).thenReturn(config(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));

        processor.deliver(prepared(), 4L);

        verify(recordService).markSent(eq(5L), eq(4L), eq("MSG-1"), eq(SmtpMailTransport.NAME), anyInt());
        verify(metrics).sent("mail", SmtpMailTransport.NAME);
        verify(recordService, never()).markPrepared(any(), anyLong());
        verifyNoInteractions(templateService, quotaGuard, sendFailureHandler);
    }

    @Test
    void thePreparedServerGone_failsTheRecord() {
        when(dispatcher.resolveSendById(10L)).thenThrow(new BusinessException("Mail send server config with ID 10 not found."));

        processor.deliver(prepared(), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("CONFIG_NOT_RESOLVABLE"), contains("not found"), any(SendFailureHandler.RecordTransitions.class));
        verify(smtpMailTransport, never()).send(any(), any());
    }

    @Test
    void rateLimitExceeded_isHandledWithoutSending() {
        when(dispatcher.resolveSendById(10L)).thenReturn(config(10L));
        when(rateLimiter.tryAcquire(eq("mail"), eq(10L), any(), any())).thenReturn(RateLimiter.Outcome.MINUTE_EXCEEDED);

        processor.deliver(prepared(), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("QUOTA_EXCEEDED"), contains("Rate limit"), any(SendFailureHandler.RecordTransitions.class));
        verify(smtpMailTransport, never()).send(any(), any());
    }

    @Test
    void providerFailureResult_isHandledAsFailure() {
        when(dispatcher.resolveSendById(10L)).thenReturn(config(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class)))
                .thenReturn(SmtpSendResult.failure("SMTP_550", "mailbox unavailable"));

        processor.deliver(prepared(), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("SMTP_550"), eq("mailbox unavailable"), any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markSent(anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void transportThrows_isHandledAsFailure() {
        when(dispatcher.resolveSendById(10L)).thenReturn(config(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenThrow(new RuntimeException("connection reset"));

        processor.deliver(prepared(), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                isNull(), eq("connection reset"), any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markSent(anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void recordingSentFails_isNotASendFailure() {
        when(dispatcher.resolveSendById(10L)).thenReturn(config(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        doThrow(new RuntimeException("lock wait timeout")).when(recordService)
                .markSent(eq(5L), eq(4L), eq("MSG-1"), eq(SmtpMailTransport.NAME), anyInt());

        // The server accepted the mail: no retry is scheduled (that would send it again),
        // and the error does not escape to the worker — the record simply stays SENDING.
        assertDoesNotThrow(() -> processor.deliver(prepared(), 4L));

        verifyNoInteractions(sendFailureHandler);
        verify(metrics).sent("mail", SmtpMailTransport.NAME);
    }

    // ---- the first attempt: preparing the record ---------------------------------------------------

    /** As acceptance stored it: a template request, nothing rendered or chosen yet. */
    private static MailSendRecord fresh(String templateCode) {
        MailSendRecord r = new MailSendRecord();
        r.setId(5L);
        r.setStatus(MailSendStatus.SENDING);
        r.setRetryCount(0);
        r.setToAddresses(List.of("alice@example.com"));
        r.setTemplateCode(templateCode);
        r.setTemplateVariables(JsonUtils.objectToJsonNode(Map.of("name", "Alice")));
        r.setPlatformScope(false);
        return r;
    }

    private MailTemplate template(String code) {
        MailTemplate template = new MailTemplate();
        template.setCode(code);
        template.setBodyMode(BodyMode.HTML);
        when(templateService.renderSubject(eq(template), any())).thenReturn("Welcome, Alice!");
        when(templateService.renderBodyHtml(eq(template), any())).thenReturn("<p>Hello Alice</p>");
        return template;
    }

    @Test
    void theFirstAttemptRendersChoosesChargesWritesBackThenSends() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME", MessageScope.TENANT)).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(config(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("WELCOME");
        Context tenant42 = new Context();
        tenant42.setTenantId(42L);

        ContextHolder.runWith(tenant42, () -> processor.deliver(record, 4L));

        assertThat(record.getSubject()).isEqualTo("Welcome, Alice!");
        assertThat(record.getBodyHtml()).isEqualTo("<p>Hello Alice</p>");
        assertThat(record.getBodyMode()).isEqualTo(BodyMode.HTML);
        assertThat(record.getServerConfigId()).isEqualTo(10L);
        assertThat(record.getFromAddress()).isEqualTo("noreply@example.com");
        verify(templateService).renderSubject(eq(template), eq(Map.of("name", "Alice")));
        verify(quotaGuard).consume("mail", 42L);
        verify(recordService).markPrepared(record, 4L);
        // markPrepared bumped the version once.
        verify(recordService).markSent(eq(5L), eq(5L), eq("MSG-1"), eq(SmtpMailTransport.NAME), anyInt());
        verifyNoInteractions(sendFailureHandler);
    }

    @Test
    void noServerConfigured_failsTheRecordWithoutChargingQuota() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME", MessageScope.TENANT)).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.TENANT))
                .thenThrow(new BusinessException("No sending mail server is configured for this tenant."));

        processor.deliver(fresh("WELCOME"), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("CONFIG_NOT_RESOLVABLE"), contains("No sending mail server"),
                any(SendFailureHandler.RecordTransitions.class));
        verifyNoInteractions(quotaGuard, smtpMailTransport);
        verify(recordService, never()).markPrepared(any(), anyLong());
    }

    @Test
    void missingTemplate_failsTheRecord() {
        when(templateService.resolve("GONE", MessageScope.TENANT))
                .thenThrow(new BusinessException("No mail template found for code GONE"));

        processor.deliver(fresh("GONE"), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("TEMPLATE_NOT_RESOLVABLE"), contains("GONE"), any(SendFailureHandler.RecordTransitions.class));
        verifyNoInteractions(dispatcher, quotaGuard, smtpMailTransport);
    }

    @Test
    void aTemplateThatFailsToRender_failsTheRecord() {
        MailTemplate template = new MailTemplate();
        template.setCode("BROKEN");
        when(templateService.resolve("BROKEN", MessageScope.TENANT)).thenReturn(template);
        when(templateService.renderSubject(eq(template), any())).thenThrow(new IllegalArgumentException("bad syntax"));

        processor.deliver(fresh("BROKEN"), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("TEMPLATE_NOT_RESOLVABLE"), contains("bad syntax"), any(SendFailureHandler.RecordTransitions.class));
        verifyNoInteractions(dispatcher, quotaGuard);
    }

    @Test
    void anEmptyRenderedBody_failsTheRecord() {
        MailTemplate template = new MailTemplate();
        template.setCode("EMPTY");
        template.setBodyMode(BodyMode.HTML);
        when(templateService.resolve("EMPTY", MessageScope.TENANT)).thenReturn(template);
        when(templateService.renderSubject(eq(template), any())).thenReturn("Subject");

        processor.deliver(fresh("EMPTY"), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("CONTENT_INVALID"), anyString(), any(SendFailureHandler.RecordTransitions.class));
        verifyNoInteractions(quotaGuard);
    }

    @Test
    void quotaUsedUp_failsTheRecordWithoutSending() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME", MessageScope.TENANT)).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(config(10L));
        doThrow(new BusinessException("The monthly mail quota is used up.")).when(quotaGuard).consume(eq("mail"), anyLong());

        processor.deliver(fresh("WELCOME"), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("MONTHLY_QUOTA_EXCEEDED"), contains("quota"), any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markPrepared(any(), anyLong());
        verifyNoInteractions(smtpMailTransport);
    }

    @Test
    void anUnexpectedErrorWhilePreparing_isLeftToTheRetryPolicy() {
        when(templateService.resolve("WELCOME", MessageScope.TENANT)).thenThrow(new IllegalStateException("db down"));

        processor.deliver(fresh("WELCOME"), 4L);

        // No marker code: the classifier reads it as UNKNOWN, which retries.
        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                isNull(), eq("db down"), any(SendFailureHandler.RecordTransitions.class));
    }

    @Test
    void writingThePreparationBackLosesTheRace_sendsNothing() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME", MessageScope.TENANT)).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(config(10L));
        when(recordService.markPrepared(any(MailSendRecord.class), eq(4L))).thenReturn(false);

        processor.deliver(fresh("WELCOME"), 4L);

        verifyNoInteractions(smtpMailTransport, sendFailureHandler);
    }

    @Test
    void theTemplatesPreferredServerIsHonored() {
        MailTemplate template = template("MARKETING");
        template.setPreferredServerConfigId(99L);
        when(templateService.resolve("MARKETING", MessageScope.TENANT)).thenReturn(template);
        when(dispatcher.resolveSendById(99L)).thenReturn(config(99L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("MARKETING");

        processor.deliver(record, 4L);

        verify(dispatcher, never()).resolveSend(any());
        assertThat(record.getServerConfigId()).isEqualTo(99L);
    }

    @Test
    void platformScopeGovernsTemplateServerAndQuotaBucket() {
        MailTemplate template = template("INVOICE_ISSUED");
        when(templateService.resolve("INVOICE_ISSUED", MessageScope.PLATFORM)).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.PLATFORM)).thenReturn(config(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("INVOICE_ISSUED");
        record.setPlatformScope(true);
        Context tenant42 = new Context();
        tenant42.setTenantId(42L);

        ContextHolder.runWith(tenant42, () -> processor.deliver(record, 4L));

        verify(templateService).resolve("INVOICE_ISSUED", MessageScope.PLATFORM);
        verify(dispatcher).resolveSend(MessageScope.PLATFORM);
        // Charged to the platform's own bucket even though the record belongs to tenant 42.
        verify(quotaGuard).consume("mail", TenantScopes.PLATFORM);
    }

    @Test
    void replyToFallsBackToTheServerDefaultAndIsFixedOnTheRecord() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME", MessageScope.TENANT)).thenReturn(template);
        MailSendServerConfig config = config(10L);
        config.setReplyToAddress("support@example.com");
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(config);
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("WELCOME");

        processor.deliver(record, 4L);

        assertThat(record.getReplyTo()).isEqualTo("support@example.com");
    }

    @Test
    void theCallersReplyToWinsOverTheServerDefault() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME", MessageScope.TENANT)).thenReturn(template);
        MailSendServerConfig config = config(10L);
        config.setReplyToAddress("support@example.com");
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(config);
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("WELCOME");
        record.setReplyTo("campaign@example.com");

        processor.deliver(record, 4L);

        assertThat(record.getReplyTo()).isEqualTo("campaign@example.com");
    }

    @Test
    void authoredPlainWithAnEmptyPlainBodyIsDerivedAndMarkedSo() {
        MailTemplate template = new MailTemplate();
        template.setCode("ALT");
        template.setBodyMode(BodyMode.HTML_WITH_AUTHORED_PLAIN);
        when(templateService.resolve("ALT", MessageScope.TENANT)).thenReturn(template);
        when(templateService.renderSubject(eq(template), any())).thenReturn("S");
        when(templateService.renderBodyHtml(eq(template), any())).thenReturn("<p>Hello <b>Alice</b></p>");
        when(templateService.renderBodyText(eq(template), any())).thenReturn("");
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(config(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("ALT");

        processor.deliver(record, 4L);

        assertThat(record.getBodyMode()).isEqualTo(BodyMode.HTML_WITH_DERIVED_PLAIN);
        assertThat(record.getBodyText()).contains("Hello").contains("Alice");
    }
}
