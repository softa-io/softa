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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A send that hands over an object of a declared set is checked against the template when it is
 * accepted; a plain map is taken as it always was.
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
    private MailMessageHandler mail;
    private SmsSendRecordService smsRecords;
    private SmsTemplateService smsTemplates;
    private SmsMessageHandler sms;

    @BeforeEach
    void setUp() {
        TemplateVariablesRegistry.register(Set.of(CodeVariables.class, OtherVariables.class));
        mailRecords = mock(MailSendRecordService.class);
        locator = mock(MailTemplateLocator.class);
        mail = new MailMessageHandler(mailRecords, locator);
        smsRecords = mock(SmsSendRecordService.class);
        smsTemplates = mock(SmsTemplateService.class);
        sms = new SmsMessageHandler(smsRecords, mock(SmsProviderDispatcher.class), smsTemplates);
    }

    @AfterEach
    void clear() {
        TemplateVariablesRegistry.register(Set.of());
    }

    private static PlatformMailTemplate platformTemplate(String code, String variableSetName) {
        PlatformMailTemplate template = new PlatformMailTemplate();
        template.setCode(code);
        template.setVariableSetName(variableSetName);
        return template;
    }

    private static MailTemplate tenantTemplate(String code, String variableSetName) {
        MailTemplate template = new MailTemplate();
        template.setCode(code);
        template.setVariableSetName(variableSetName);
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
        when(locator.find(null, "free", MessageScope.TENANT)).thenReturn(tenantTemplate("free", null));

        MailSendRecord record = sendMail(SendMailDTO.ofTemplate(List.of("a@example.com"), "free",
                new OtherVariables("https://x"), MessageScope.TENANT));

        assertEquals(Map.of("link", "https://x"), JsonUtils.jsonNodeToMap(record.getTemplateVariables()));
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
        verifyNoInteractions(locator);
    }

    @Test
    void theSetNameCannotComeFromARequestBody() {
        SendMailDTO parsed = JsonUtils.stringToObject(
                "{\"to\":[\"a@example.com\"],\"templateCode\":\"order.notice\","
                        + "\"variableSetName\":\"test.code\"}", SendMailDTO.class);
        assertNull(parsed.getVariableSetName());
        assertEquals("order.notice", parsed.getTemplateCode());
        SendSmsDTO sms = JsonUtils.stringToObject(
                "{\"phoneNumber\":\"+6591234567\",\"templateCode\":\"x\",\"variableSetName\":\"test.code\"}",
                SendSmsDTO.class);
        assertNull(sms.getVariableSetName());
    }

    // ---- sms ----

    private static SmsTemplate smsTemplate(String code, String variableSetName) {
        SmsTemplate template = new SmsTemplate();
        template.setCode(code);
        template.setVariableSetName(variableSetName);
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
