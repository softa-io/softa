package io.softa.starter.message.sms.controller;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.enums.Language;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.dto.SmsSendStatusDTO;
import io.softa.starter.message.sms.dto.SmsTemplateSummaryDTO;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.enums.SmsDeliveryStatus;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.service.SmsSendService;
import io.softa.starter.message.sms.service.SmsTemplateService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SmsApiControllerTest {

    private SmsApiController controller;
    private SmsSendService smsSendService;
    private SmsSendRecordService sendRecordService;
    private SmsTemplateService templateService;

    @BeforeEach
    void setUp() {
        controller = new SmsApiController();
        smsSendService = mock(SmsSendService.class);
        sendRecordService = mock(SmsSendRecordService.class);
        templateService = mock(SmsTemplateService.class);

        ReflectionTestUtils.setField(controller, "smsSendService", smsSendService);
        ReflectionTestUtils.setField(controller, "sendRecordService", sendRecordService);
        ReflectionTestUtils.setField(controller, "templateService", templateService);
    }

    // ========== Status ==========

    @Test
    void getStatusReturnsDto() {
        SmsSendRecord record = new SmsSendRecord();
        record.setId(1L);
        record.setProviderType(SmsProvider.TWILIO);
        record.setPhoneNumber("+8613800138000");
        record.setContentPreview("Hello");
        record.setStatus(SmsSendStatus.SENT);
        record.setRetryCount(0);
        record.setSentAt(LocalDateTime.of(2026, 4, 10, 10, 0));
        record.setProviderMessageId("SM123");
        record.setDeliveryStatus(SmsDeliveryStatus.DELIVERED);
        record.setDeliveryStatusUpdatedAt(LocalDateTime.of(2026, 4, 10, 10, 5));

        when(sendRecordService.getById(1L)).thenReturn(Optional.of(record));

        ApiResponse<SmsSendStatusDTO> response = controller.getStatus(1L);
        Assertions.assertNotNull(response.getData());

        SmsSendStatusDTO dto = response.getData();
        Assertions.assertEquals(1L, dto.getId());
        Assertions.assertEquals(SmsProvider.TWILIO, dto.getProviderType());
        Assertions.assertEquals("+8613800138000", dto.getPhoneNumber());
        Assertions.assertEquals(SmsSendStatus.SENT, dto.getStatus());
        Assertions.assertEquals("SM123", dto.getProviderMessageId());
        Assertions.assertEquals(SmsDeliveryStatus.DELIVERED, dto.getDeliveryStatus());
    }

    @Test
    void getStatusReturnsErrorCodeOnFailure() {
        SmsSendRecord record = new SmsSendRecord();
        record.setId(2L);
        record.setProviderType(SmsProvider.ALIYUN);
        record.setPhoneNumber("+8613800138000");
        record.setStatus(SmsSendStatus.FAILED);
        record.setRetryCount(0);
        record.setErrorCode("isv.BUSINESS_LIMIT_CONTROL");
        record.setErrorMessage("Sending frequency exceeds limit");
        record.setDeliveryStatus(SmsDeliveryStatus.UNKNOWN);

        when(sendRecordService.getById(2L)).thenReturn(Optional.of(record));

        ApiResponse<SmsSendStatusDTO> response = controller.getStatus(2L);
        Assertions.assertNotNull(response.getData());

        SmsSendStatusDTO dto = response.getData();
        Assertions.assertEquals(SmsProvider.ALIYUN, dto.getProviderType());
        Assertions.assertEquals("isv.BUSINESS_LIMIT_CONTROL", dto.getErrorCode());
        Assertions.assertEquals("Sending frequency exceeds limit", dto.getErrorMessage());
        Assertions.assertEquals(SmsSendStatus.FAILED, dto.getStatus());
    }

    @Test
    void getStatusReturnsNullForNonexistent() {
        when(sendRecordService.getById(99L)).thenReturn(Optional.empty());

        ApiResponse<SmsSendStatusDTO> response = controller.getStatus(99L);
        Assertions.assertNull(response.getData());
    }

    // ========== Templates ==========

    @Test
    void listTemplatesReturnsSummaries() {
        SmsTemplate template = new SmsTemplate();
        template.setId(1L);
        template.setCode("VERIFY_CODE");
        template.setName("Verification Code");
        template.setDescription("SMS verification code");
        template.setLanguage(Language.EN_US);
        template.setContent("Your verification code is {{ code }}. Valid for 5 minutes.");

        when(templateService.searchList(any(Filters.class))).thenReturn(List.of(template));

        ApiResponse<List<SmsTemplateSummaryDTO>> response = controller.listTemplates();
        Assertions.assertNotNull(response.getData());
        Assertions.assertEquals(1, response.getData().size());

        SmsTemplateSummaryDTO dto = response.getData().getFirst();
        Assertions.assertEquals("VERIFY_CODE", dto.getCode());
        Assertions.assertEquals("Verification Code", dto.getName());
        Assertions.assertEquals("en-US", dto.getLanguage().getCode());
    }

    @Test
    void listTemplatesReturnsEmptyWhenNoneEnabled() {
        when(templateService.searchList(any(Filters.class))).thenReturn(List.of());

        ApiResponse<List<SmsTemplateSummaryDTO>> response = controller.listTemplates();
        Assertions.assertNotNull(response.getData());
        Assertions.assertTrue(response.getData().isEmpty());
    }

    @Test
    void listTemplatesTruncatesContentPreviewTo100Chars() {
        SmsTemplate template = new SmsTemplate();
        template.setId(1L);
        template.setCode("LONG");
        template.setName("Long Template");
        template.setLanguage(Language.EN_US);
        template.setContent("A".repeat(200));

        when(templateService.searchList(any(Filters.class))).thenReturn(List.of(template));

        ApiResponse<List<SmsTemplateSummaryDTO>> response = controller.listTemplates();
        Assertions.assertEquals(100, response.getData().getFirst().getContentPreview().length());
    }

    // ========== Send by Template ==========

    @Test
    void sendByTemplateCallsService() {
        controller.sendByTemplate("VERIFY_CODE", List.of("+8613800138000"),
                Map.of("code", "123456"));

        verify(smsSendService).sendByTemplate("VERIFY_CODE", List.of("+8613800138000"),
                Map.of("code", "123456"));
    }

    @Test
    void sendByTemplateHandlesNullVariables() {
        controller.sendByTemplate("SIMPLE", List.of("+8613800138000"), null);

        verify(smsSendService).sendByTemplate("SIMPLE", List.of("+8613800138000"),
                Collections.emptyMap());
    }

    @Test
    void sendAsyncDelegatesService() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+8613800138000");
        dto.setContent("Async test");

        controller.sendAsync(dto);

        verify(smsSendService).sendAsync(dto);
    }
}
