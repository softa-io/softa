package io.softa.starter.message.service.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.annotation.TemplateVariables;
import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.meta.TemplateVariablesRegistry;
import io.softa.starter.message.mail.dto.SendMailDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.entity.PlatformMailTemplate;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.support.MailTemplateLocator;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsTemplateService;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;
import io.softa.starter.message.template.TemplateRecordReader;
import io.softa.starter.message.template.TemplateUsage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A send that names where its values come from — an object of a declared set, or a record — is
 * checked against the template when it is accepted; a plain map is taken as it always was.
 */
class MessageSourcedVariablesTest {

    @TemplateVariables(name = "test.code")
    public record CodeVariables(String code, Integer expiryMinutes, String employeeName) {
    }

    @TemplateVariables(name = "test.other")
    public record OtherVariables(String link) {
    }

    private MailSendRecordService mailRecords;
    private MailTemplateLocator locator;
    private TemplateRecordReader reader;
    private MailMessageHandler mail;
    private SmsSendRecordService smsRecords;
    private SmsTemplateService smsTemplates;
    private SmsMessageHandler sms;

    @BeforeEach
    void setUp() {
        TemplateVariablesRegistry.register(Set.of(CodeVariables.class, OtherVariables.class));
        mailRecords = mock(MailSendRecordService.class);
        locator = mock(MailTemplateLocator.class);
        reader = mock(TemplateRecordReader.class);
        mail = new MailMessageHandler(mailRecords, locator, reader);
        smsRecords = mock(SmsSendRecordService.class);
        smsTemplates = mock(SmsTemplateService.class);
        sms = new SmsMessageHandler(smsRecords, mock(SmsProviderDispatcher.class), smsTemplates);
    }

    @AfterEach
    void clear() {
        TemplateVariablesRegistry.register(Set.of());
    }

    private static PlatformMailTemplate platformTemplate(String code, String variablesName) {
        PlatformMailTemplate template = new PlatformMailTemplate();
        template.setCode(code);
        template.setVariablesName(variablesName);
        return template;
    }

    private static MailTemplate tenantTemplate(String code, String variablesName, String modelName) {
        MailTemplate template = new MailTemplate();
        template.setCode(code);
        template.setVariablesName(variablesName);
        template.setModelName(modelName);
        template.setSubject("Order {{ code }}");
        template.setBodyHtml("<p>{{ customerId.name }}</p>");
        return template;
    }

    private MailSendRecord sendMail(SendMailDTO dto) {
        ContextHolder.runWith(new Context("trace-1"), () -> mail.send(dto));
        ArgumentCaptor<MailSendRecord> captor = ArgumentCaptor.forClass(MailSendRecord.class);
        verify(mailRecords).createOne(captor.capture());
        return captor.getValue();
    }

    private BusinessException refusedMail(SendMailDTO dto) {
        BusinessException e = assertThrows(BusinessException.class,
                () -> ContextHolder.runWith(new Context("trace-1"), () -> mail.send(dto)));
        verify(mailRecords, never()).createOne(any(MailSendRecord.class));
        return e;
    }

    // ---- mail: an object ----

    @Test
    void anObjectBecomesTheTemplateVariables_missingValuesEmpty() {
        when(locator.find(null, "user.verification-code", MessageScope.PLATFORM))
                .thenReturn(platformTemplate("user.verification-code", "test.code"));

        MailSendRecord record = sendMail(SendMailDTO.ofTemplate(List.of("a@example.com"), "user.verification-code",
                new CodeVariables("123456", 10, null), MessageScope.PLATFORM));

        assertEquals(Map.of("code", "123456", "expiryMinutes", 10, "employeeName", ""),
                JsonUtils.jsonNodeToMap(record.getTemplateVariables()));
        assertEquals("user.verification-code", record.getTemplateCode());
    }

    @Test
    void anObjectOfAnotherSetIsRefused_andNothingIsStored() {
        when(locator.find(null, "user.verification-code", MessageScope.PLATFORM))
                .thenReturn(platformTemplate("user.verification-code", "test.code"));

        BusinessException e = refusedMail(SendMailDTO.ofTemplate(List.of("a@example.com"), "user.verification-code",
                new OtherVariables("https://x"), MessageScope.PLATFORM));

        assertTrue(e.getMessage().contains("'test.code'") && e.getMessage().contains("'test.other'"), e.getMessage());
    }

    @Test
    void aTemplateThatNamesNoSetTakesAnyObject() {
        when(locator.find(null, "free", MessageScope.TENANT)).thenReturn(tenantTemplate("free", null, null));

        MailSendRecord record = sendMail(SendMailDTO.ofTemplate(List.of("a@example.com"), "free",
                new OtherVariables("https://x"), MessageScope.TENANT));

        assertEquals(Map.of("link", "https://x"), JsonUtils.jsonNodeToMap(record.getTemplateVariables()));
    }

    @Test
    void anObjectSentToAModelBoundTemplateIsRefused() {
        when(locator.find(null, "order.notice", MessageScope.TENANT))
                .thenReturn(tenantTemplate("order.notice", null, "Order"));

        BusinessException e = refusedMail(SendMailDTO.ofTemplate(List.of("a@example.com"), "order.notice",
                new OtherVariables("https://x"), MessageScope.TENANT));

        assertTrue(e.getMessage().contains("ofRecord"), e.getMessage());
    }

    @Test
    void anObjectForATemplateThatCannotBeFoundIsAccepted_theFirstAttemptReportsIt() {
        when(locator.find(null, "gone", MessageScope.PLATFORM)).thenThrow(new BusinessException("No mail template"));

        MailSendRecord record = sendMail(SendMailDTO.ofTemplate(List.of("a@example.com"), "gone",
                new OtherVariables("https://x"), MessageScope.PLATFORM));

        assertEquals("gone", record.getTemplateCode());
    }

    @Test
    void aPlainMapIsNeverCheckedAndTheTemplateIsNotLookedUp() {
        MailSendRecord record = sendMail(SendMailDTO.ofTemplate(List.of("a@example.com"), "user.verification-code",
                Map.of("anything", "goes"), MessageScope.PLATFORM));

        assertEquals(Map.of("anything", "goes"), JsonUtils.jsonNodeToMap(record.getTemplateVariables()));
        verifyNoInteractions(locator, reader);
    }

    // ---- mail: a record ----

    @Test
    void aRecordIsReadForWhatTheTemplateUses_andStoredAsTheVariables() {
        MailTemplate template = tenantTemplate("order.notice", null, "Order");
        when(locator.find(null, "order.notice", MessageScope.TENANT)).thenReturn(template);
        when(reader.read(eq("Order"), eq(7L), any(TemplateUsage.class)))
                .thenReturn(Map.of("code", "SO-7", "customerId", Map.of("name", "Acme")));

        MailSendRecord record = sendMail(SendMailDTO.ofRecord(List.of("a@example.com"), "order.notice", 7L,
                MessageScope.TENANT));

        assertEquals(Map.of("code", "SO-7", "customerId", Map.of("name", "Acme")),
                JsonUtils.jsonNodeToMap(record.getTemplateVariables()));
        ArgumentCaptor<TemplateUsage> usage = ArgumentCaptor.forClass(TemplateUsage.class);
        verify(reader).read(eq("Order"), eq(7L), usage.capture());
        assertEquals(Set.of("code", "customerId.name"), usage.getValue().printed());
    }

    @Test
    void aRecordNeedsATemplateBoundToAModel() {
        when(locator.find(null, "user.verification-code", MessageScope.PLATFORM))
                .thenReturn(platformTemplate("user.verification-code", "test.code"));
        BusinessException e = refusedMail(SendMailDTO.ofRecord(List.of("a@example.com"), "user.verification-code",
                7L, MessageScope.PLATFORM));
        assertTrue(e.getMessage().contains("not bound to a model"), e.getMessage());
        verifyNoInteractions(reader);
    }

    @Test
    void aRecordForATemplateThatCannotBeFoundIsRefusedNow() {
        when(locator.find(null, "gone", MessageScope.TENANT)).thenThrow(new BusinessException("No mail template found"));
        BusinessException e = refusedMail(SendMailDTO.ofRecord(List.of("a@example.com"), "gone", 7L,
                MessageScope.TENANT));
        assertTrue(e.getMessage().contains("No mail template found"), e.getMessage());
    }

    @Test
    void theRecordAndTheSetNameCannotComeFromARequestBody() {
        SendMailDTO parsed = JsonUtils.stringToObject(
                "{\"to\":[\"a@example.com\"],\"templateCode\":\"order.notice\",\"recordId\":7,"
                        + "\"variablesName\":\"test.code\"}", SendMailDTO.class);
        assertNull(parsed.getRecordId());
        assertNull(parsed.getVariablesName());
        assertEquals("order.notice", parsed.getTemplateCode());
        SendSmsDTO sms = JsonUtils.stringToObject(
                "{\"phoneNumber\":\"+6591234567\",\"templateCode\":\"x\",\"variablesName\":\"test.code\"}",
                SendSmsDTO.class);
        assertNull(sms.getVariablesName());
    }

    // ---- sms ----

    private static SmsTemplate smsTemplate(String code, String variablesName) {
        SmsTemplate template = new SmsTemplate();
        template.setCode(code);
        template.setVariablesName(variablesName);
        return template;
    }

    @Test
    void anSmsObjectOfTheTemplatesSetIsAccepted() {
        when(smsTemplates.resolve("user.verification-code")).thenReturn(smsTemplate("user.verification-code", "test.code"));

        ContextHolder.runWith(new Context("trace-1"), () -> sms.send(SendSmsDTO.ofTemplate("+6591234567",
                "user.verification-code", new CodeVariables("123456", 10, null), MessageScope.PLATFORM)));

        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(smsRecords).createOne(captor.capture());
        // The value left out is not sent: a provider template takes exactly its own parameters.
        assertEquals(Map.of("code", "123456", "expiryMinutes", 10),
                JsonUtils.jsonNodeToMap(captor.getValue().getTemplateVariables()));
    }

    @Test
    void anSmsObjectOfAnotherSetIsRefused() {
        when(smsTemplates.resolve("user.verification-code")).thenReturn(smsTemplate("user.verification-code", "test.code"));

        BusinessException e = assertThrows(BusinessException.class, () -> ContextHolder.runWith(new Context("trace-1"),
                () -> sms.send(SendSmsDTO.ofTemplate("+6591234567", "user.verification-code",
                        new OtherVariables("https://x"), MessageScope.PLATFORM))));

        assertTrue(e.getMessage().contains("'test.code'") && e.getMessage().contains("'test.other'"), e.getMessage());
        verify(smsRecords, never()).createOne(any(SmsSendRecord.class));
    }

    @Test
    void anSmsMapIsNeverChecked() {
        ContextHolder.runWith(new Context("trace-1"), () -> sms.send(SendSmsDTO.ofTemplate("+6591234567",
                "user.verification-code", Map.of("code", "1"), MessageScope.PLATFORM)));

        verifyNoInteractions(smsTemplates);
        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(smsRecords).createOne(captor.capture());
        assertFalse(captor.getValue().getTemplateVariables().isEmpty());
    }
}
