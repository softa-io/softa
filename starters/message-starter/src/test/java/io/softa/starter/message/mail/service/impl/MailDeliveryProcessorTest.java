package io.softa.starter.message.mail.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.config.SystemConfig;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.VersionException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.mail.entity.AbstractMailTemplate;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.entity.PlatformMailSendServer;
import io.softa.starter.message.mail.entity.PlatformMailTemplate;
import io.softa.starter.message.mail.enums.BodyMode;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.enums.MailServerOwner;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.MailTemplateService;
import io.softa.starter.message.mail.service.PlatformMailTemplateService;
import io.softa.starter.message.mail.smtp.SmtpMailRequest;
import io.softa.starter.message.mail.smtp.SmtpMailTransport;
import io.softa.starter.message.mail.smtp.SmtpSendResult;
import io.softa.starter.message.mail.support.MailServerDispatcher;
import io.softa.starter.message.mail.support.MailServerDispatcher.SendServer;
import io.softa.starter.message.shared.ErrorClassifier;
import io.softa.starter.message.shared.SendRejection;
import io.softa.starter.message.shared.SendQuotaGuard;
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
    private PlatformMailTemplateService platformTemplateService;
    private SendQuotaGuard quotaGuard;
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
        platformTemplateService = mock(PlatformMailTemplateService.class);
        quotaGuard = mock(SendQuotaGuard.class);
        smtpMailTransport = mock(SmtpMailTransport.class);
        recordService = mock(MailSendRecordService.class);
        rateLimiter = mock(RateLimiter.class);
        sendFailureHandler = mock(SendFailureHandler.class);
        metrics = mock(MessageMetrics.class);

        processor = new MailDeliveryProcessor();
        ReflectionTestUtils.setField(processor, "dispatcher", dispatcher);
        ReflectionTestUtils.setField(processor, "templateService", templateService);
        ReflectionTestUtils.setField(processor, "platformTemplateService", platformTemplateService);
        ReflectionTestUtils.setField(processor, "quotaGuard", quotaGuard);
        ReflectionTestUtils.setField(processor, "smtpMailTransport", smtpMailTransport);
        ReflectionTestUtils.setField(processor, "recordService", recordService);
        ReflectionTestUtils.setField(processor, "rateLimiter", rateLimiter);
        ReflectionTestUtils.setField(processor, "sendFailureHandler", sendFailureHandler);
        ReflectionTestUtils.setField(processor, "metrics", metrics);

        when(rateLimiter.tryAcquire(eq("mail"), anyLong(), any(), any())).thenReturn(RateLimiter.Outcome.ALLOWED);
        when(recordService.markPrepared(any(MailSendRecord.class), anyLong())).thenReturn(true);
        SystemConfig config = new SystemConfig();
        config.setEnableMultiTenancy(true);
        SystemConfig.env = config;
    }

    @AfterEach
    void tearDown() {
        SystemConfig.env = null;
    }

    // ---- a prepared record: replayed as it is -----------------------------------------------------

    /** Prepared on an earlier attempt: content, server and sender are fixed. */
    private static MailSendRecord prepared() {
        MailSendRecord r = new MailSendRecord();
        r.setId(5L);
        r.setStatus(MailSendStatus.SENDING);
        r.setRetryCount(0);
        r.setServerConfigId(10L);
        r.setServerOwner(MailServerOwner.TENANT);
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

    /** One of the tenant's own send servers. */
    private static SendServer tenant(long id) {
        return new SendServer(config(id), MailServerOwner.TENANT);
    }

    /** A platform send server: the platform's own mail, or a tenant's relayed. */
    private static SendServer platform(long id) {
        PlatformMailSendServer c = new PlatformMailSendServer();
        c.setId(id);
        c.setFromAddress("noreply@platform.example.com");
        return new SendServer(c, MailServerOwner.PLATFORM);
    }

    @Test
    void aPreparedRecordIsSentWithoutBeingPreparedAgain() {
        when(dispatcher.resolveSendById(MailServerOwner.TENANT, 10L)).thenReturn(tenant(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));

        processor.deliver(prepared(), 4L);

        verify(recordService).markSent(eq(5L), eq(4L), eq("MSG-1"), eq(SmtpMailTransport.NAME), anyInt());
        verify(metrics).sent("mail", SmtpMailTransport.NAME);
        verify(recordService, never()).markPrepared(any(), anyLong());
        verifyNoInteractions(templateService, quotaGuard, sendFailureHandler);
    }

    @Test
    void thePreparedServerGone_failsTheRecord() {
        when(dispatcher.resolveSendById(MailServerOwner.TENANT, 10L)).thenThrow(new BusinessException("Mail send server config with ID 10 not found."));

        processor.deliver(prepared(), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("CONFIG_NOT_RESOLVABLE"), contains("not found"), any(SendFailureHandler.RecordTransitions.class));
        verify(smtpMailTransport, never()).send(any(), any());
    }

    @Test
    void rateLimitExceeded_isHandledWithoutSending() {
        when(dispatcher.resolveSendById(MailServerOwner.TENANT, 10L)).thenReturn(tenant(10L));
        when(rateLimiter.tryAcquire(eq("mail"), eq(10L), any(), any())).thenReturn(RateLimiter.Outcome.MINUTE_EXCEEDED);

        processor.deliver(prepared(), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("QUOTA_EXCEEDED"), contains("Rate limit"), any(SendFailureHandler.RecordTransitions.class));
        verify(smtpMailTransport, never()).send(any(), any());
    }

    @Test
    void providerFailureResult_isHandledAsFailure() {
        when(dispatcher.resolveSendById(MailServerOwner.TENANT, 10L)).thenReturn(tenant(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class)))
                .thenReturn(SmtpSendResult.failure("SMTP_550", "mailbox unavailable"));

        processor.deliver(prepared(), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("SMTP_550"), eq("mailbox unavailable"), any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markSent(anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void transportThrows_isHandledAsFailure() {
        when(dispatcher.resolveSendById(MailServerOwner.TENANT, 10L)).thenReturn(tenant(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenThrow(new RuntimeException("connection reset"));

        processor.deliver(prepared(), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                isNull(), eq("connection reset"), any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markSent(anyLong(), anyLong(), any(), any(), anyInt());
    }

    @Test
    void recordingSentFails_isNotASendFailure() {
        when(dispatcher.resolveSendById(MailServerOwner.TENANT, 10L)).thenReturn(tenant(10L));
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
        return stubRendering(new MailTemplate(), code);
    }

    private PlatformMailTemplate platformTemplate(String code) {
        return stubRendering(new PlatformMailTemplate(), code);
    }

    private <T extends AbstractMailTemplate> T stubRendering(T template, String code) {
        template.setCode(code);
        template.setBodyMode(BodyMode.HTML);
        when(templateService.renderSubject(eq(template), any())).thenReturn("Welcome, Alice!");
        when(templateService.renderBodyHtml(eq(template), any())).thenReturn("<p>Hello Alice</p>");
        return template;
    }

    @Test
    void theFirstAttemptRendersChoosesChargesWritesBackThenSends() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME")).thenReturn(template);
        // The tenant has no send server of its own: the platform's relays its mail, and charges it.
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(platform(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("WELCOME");
        record.setTenantId(42L);
        Context tenant42 = new Context();
        tenant42.setTenantId(42L);

        ContextHolder.runWith(tenant42, () -> processor.deliver(record, 4L));

        assertThat(record.getSubject()).isEqualTo("Welcome, Alice!");
        assertThat(record.getBodyHtml()).isEqualTo("<p>Hello Alice</p>");
        assertThat(record.getBodyMode()).isEqualTo(BodyMode.HTML);
        assertThat(record.getServerConfigId()).isEqualTo(10L);
        assertThat(record.getServerOwner()).isEqualTo(MailServerOwner.PLATFORM);
        assertThat(record.getFromAddress()).isEqualTo("noreply@platform.example.com");
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
        when(templateService.resolve("WELCOME")).thenReturn(template);
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
        when(templateService.resolve("GONE"))
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
        when(templateService.resolve("BROKEN")).thenReturn(template);
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
        when(templateService.resolve("EMPTY")).thenReturn(template);
        when(templateService.renderSubject(eq(template), any())).thenReturn("Subject");

        processor.deliver(fresh("EMPTY"), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("CONTENT_INVALID"), anyString(), any(SendFailureHandler.RecordTransitions.class));
        verifyNoInteractions(quotaGuard);
    }

    @Test
    void quotaUsedUp_failsTheRecordWithoutSending() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME")).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(platform(10L));
        doThrow(new SendRejection(ErrorClassifier.MONTHLY_QUOTA_EXCEEDED, "The monthly mail quota is used up."))
                .when(quotaGuard).consume(eq("mail"), anyLong());

        processor.deliver(fresh("WELCOME"), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("MONTHLY_QUOTA_EXCEEDED"), contains("quota"), any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markPrepared(any(), anyLong());
        verifyNoInteractions(smtpMailTransport);
    }

    @Test
    void todaysCapReached_failsTheRecordWithTheDailyCode() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME")).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(platform(10L));
        doThrow(new SendRejection(ErrorClassifier.DAILY_QUOTA_EXCEEDED, "Daily mail send quota (2000) is used up for today."))
                .when(quotaGuard).consume(eq("mail"), anyLong());

        processor.deliver(fresh("WELCOME"), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("DAILY_QUOTA_EXCEEDED"), contains("today"), any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markPrepared(any(), anyLong());
        verifyNoInteractions(smtpMailTransport);
    }

    @Test
    void quotaCounterContention_isLeftToTheRetryPolicy() {
        // Other sends of the bucket kept winning the counter's race: not "quota used up", so no
        // MONTHLY_QUOTA_EXCEEDED marker that would fail the record for good.
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME")).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(platform(10L));
        doThrow(new VersionException("The usage counter kept changing; try again."))
                .when(quotaGuard).consume(eq("mail"), anyLong());

        processor.deliver(fresh("WELCOME"), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                isNull(), contains("try again"), any(SendFailureHandler.RecordTransitions.class));
        verify(recordService, never()).markPrepared(any(), anyLong());
        verifyNoInteractions(smtpMailTransport);
    }

    @Test
    void anUnexpectedErrorWhilePreparing_isLeftToTheRetryPolicy() {
        when(templateService.resolve("WELCOME")).thenThrow(new IllegalStateException("db down"));

        processor.deliver(fresh("WELCOME"), 4L);

        // No marker code: the classifier reads it as UNKNOWN, which retries.
        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                isNull(), eq("db down"), any(SendFailureHandler.RecordTransitions.class));
    }

    @Test
    void writingThePreparationBackLosesTheRace_sendsNothing() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME")).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(tenant(10L));
        when(recordService.markPrepared(any(MailSendRecord.class), eq(4L))).thenReturn(false);

        processor.deliver(fresh("WELCOME"), 4L);

        verifyNoInteractions(smtpMailTransport, sendFailureHandler);
    }

    @Test
    void theTemplatesPreferredServerIsHonored() {
        MailTemplate template = template("MARKETING");
        template.setPreferredServerConfigId(99L);
        when(templateService.resolve("MARKETING")).thenReturn(template);
        when(dispatcher.resolveSendById(MailServerOwner.TENANT, 99L)).thenReturn(tenant(99L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("MARKETING");

        processor.deliver(record, 4L);

        verify(dispatcher, never()).resolveSend(any());
        assertThat(record.getServerConfigId()).isEqualTo(99L);
    }

    @Test
    void platformScopeGovernsTemplateAndServerWhileTheRecordsTenantPays() {
        PlatformMailTemplate template = platformTemplate("INVOICE_ISSUED");
        when(platformTemplateService.resolve("INVOICE_ISSUED")).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.PLATFORM)).thenReturn(platform(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("INVOICE_ISSUED");
        record.setPlatformScope(true);
        record.setTenantId(42L);
        Context tenant42 = new Context();
        tenant42.setTenantId(42L);

        ContextHolder.runWith(tenant42, () -> processor.deliver(record, 4L));

        verify(platformTemplateService).resolve("INVOICE_ISSUED");
        verify(templateService, never()).resolve(any());
        verify(dispatcher).resolveSend(MessageScope.PLATFORM);
        // The platform's template and server, but a notice made for tenant 42: its quota pays.
        verify(quotaGuard).consume("mail", 42L);
    }

    @Test
    void aPlatformSendFiledUnderNoTenantDrawsOnThePlatformBucket() {
        PlatformMailTemplate template = platformTemplate("INVOICE_ISSUED");
        when(platformTemplateService.resolve("INVOICE_ISSUED")).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.PLATFORM)).thenReturn(platform(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("INVOICE_ISSUED");
        record.setPlatformScope(true);
        record.setTenantId(TenantScopes.PLATFORM);

        processor.deliver(record, 4L);

        verify(quotaGuard).consume("mail", TenantScopes.PLATFORM);
    }

    @Test
    void replyToFallsBackToTheServerDefaultAndIsFixedOnTheRecord() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME")).thenReturn(template);
        MailSendServerConfig config = config(10L);
        config.setReplyToAddress("support@example.com");
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(new SendServer(config, MailServerOwner.TENANT));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("WELCOME");

        processor.deliver(record, 4L);

        assertThat(record.getReplyTo()).isEqualTo("support@example.com");
    }

    @Test
    void theCallersReplyToWinsOverTheServerDefault() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME")).thenReturn(template);
        MailSendServerConfig config = config(10L);
        config.setReplyToAddress("support@example.com");
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(new SendServer(config, MailServerOwner.TENANT));
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
        when(templateService.resolve("ALT")).thenReturn(template);
        when(templateService.renderSubject(eq(template), any())).thenReturn("S");
        when(templateService.renderBodyHtml(eq(template), any())).thenReturn("<p>Hello <b>Alice</b></p>");
        when(templateService.renderBodyText(eq(template), any())).thenReturn("");
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(tenant(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("ALT");

        processor.deliver(record, 4L);

        assertThat(record.getBodyMode()).isEqualTo(BodyMode.HTML_WITH_DERIVED_PLAIN);
        assertThat(record.getBodyText()).contains("Hello").contains("Alice");
    }

    // ---- tiers: whose server, whose template -----------------------------------------------------

    @Test
    void aTenantsOwnServerCostsNoQuota() {
        MailTemplate template = template("WELCOME");
        when(templateService.resolve("WELCOME")).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.TENANT)).thenReturn(tenant(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("WELCOME");

        processor.deliver(record, 4L);

        assertThat(record.getServerOwner()).isEqualTo(MailServerOwner.TENANT);
        verifyNoInteractions(quotaGuard);
        verify(recordService).markSent(eq(5L), eq(5L), eq("MSG-1"), eq(SmtpMailTransport.NAME), anyInt());
    }

    @Test
    void aPlatformCodeSentAsTenant_failsSayingWhichScopeItNeeds() {
        when(templateService.resolve("user.password-reset"))
                .thenThrow(new BusinessException("No mail template found for code 'user.password-reset'."));
        when(platformTemplateService.findAnyByCode("user.password-reset"))
                .thenReturn(Optional.of(new PlatformMailTemplate()));

        processor.deliver(fresh("user.password-reset"), 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("TEMPLATE_NOT_RESOLVABLE"), contains("send it with scope PLATFORM"),
                any(SendFailureHandler.RecordTransitions.class));
        verifyNoInteractions(dispatcher, quotaGuard, smtpMailTransport);
    }

    @Test
    void aTenantCodeSentAsPlatform_failsSayingWhichScopeItNeeds() {
        when(platformTemplateService.resolve("flow.approved"))
                .thenThrow(new BusinessException("No mail template found for code 'flow.approved'."));
        when(templateService.anyTenantHasCode("flow.approved")).thenReturn(true);
        MailSendRecord record = fresh("flow.approved");
        record.setPlatformScope(true);

        processor.deliver(record, 4L);

        verify(sendFailureHandler).handle(eq("mail"), eq(SmtpMailTransport.NAME), eq(5L), eq(4L), eq(0),
                eq("TEMPLATE_NOT_RESOLVABLE"), contains("send it with scope TENANT"),
                any(SendFailureHandler.RecordTransitions.class));
        verify(templateService, never()).resolve(any());
    }

    @Test
    void aPlatformSendPinsAPlatformServer() {
        PlatformMailTemplate template = platformTemplate("INVOICE_ISSUED");
        template.setPreferredServerConfigId(77L);
        when(platformTemplateService.resolve("INVOICE_ISSUED")).thenReturn(template);
        when(dispatcher.resolveSendById(MailServerOwner.PLATFORM, 77L)).thenReturn(platform(77L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("INVOICE_ISSUED");
        record.setPlatformScope(true);

        processor.deliver(record, 4L);

        verify(dispatcher, never()).resolveSendById(eq(MailServerOwner.TENANT), anyLong());
        assertThat(record.getServerOwner()).isEqualTo(MailServerOwner.PLATFORM);
    }


    @Test
    void withMultiTenancyOff_theDeploymentsOwnTemplatesComeFirstThenThePlatformTables() {
        SystemConfig.env = null;
        when(templateService.resolve("user.password-reset"))
                .thenThrow(new BusinessException("No mail template found for code 'user.password-reset'."));
        PlatformMailTemplate template = platformTemplate("user.password-reset");
        when(platformTemplateService.resolve("user.password-reset")).thenReturn(template);
        when(dispatcher.resolveSend(MessageScope.PLATFORM)).thenReturn(platform(10L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("user.password-reset");
        record.setPlatformScope(true);

        processor.deliver(record, 4L);

        assertThat(record.getSubject()).isEqualTo("Welcome, Alice!");
        verifyNoInteractions(sendFailureHandler);
    }

    @Test
    void withMultiTenancyOff_aPinnedServerIsTakenFromWhicheverTableHoldsIt() {
        SystemConfig.env = null;
        PlatformMailTemplate template = platformTemplate("INVOICE_ISSUED");
        template.setPreferredServerConfigId(77L);
        when(templateService.resolve("INVOICE_ISSUED"))
                .thenThrow(new BusinessException("No mail template found for code 'INVOICE_ISSUED'."));
        when(platformTemplateService.resolve("INVOICE_ISSUED")).thenReturn(template);
        when(dispatcher.ownerOf(77L)).thenReturn(MailServerOwner.PLATFORM);
        when(dispatcher.resolveSendById(MailServerOwner.PLATFORM, 77L)).thenReturn(platform(77L));
        when(smtpMailTransport.send(any(), any(SmtpMailRequest.class))).thenReturn(SmtpSendResult.success("MSG-1"));
        MailSendRecord record = fresh("INVOICE_ISSUED");

        processor.deliver(record, 4L);

        assertThat(record.getServerConfigId()).isEqualTo(77L);
        assertThat(record.getServerOwner()).isEqualTo(MailServerOwner.PLATFORM);
        verifyNoInteractions(sendFailureHandler);
    }
}
