package io.softa.starter.message.mail.controller;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.enums.Language;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.web.response.ApiResponse;
import io.softa.starter.message.mail.dto.MailSendStatusDTO;
import io.softa.starter.message.mail.dto.MailSenderSummaryDTO;
import io.softa.starter.message.mail.dto.MailTemplatePreviewDTO;
import io.softa.starter.message.mail.dto.MailTemplateSummaryDTO;
import io.softa.starter.message.mail.entity.MailSendRecord;
import io.softa.starter.message.mail.entity.MailSendServerConfig;
import io.softa.starter.message.mail.entity.MailTemplate;
import io.softa.starter.message.mail.enums.MailSendStatus;
import io.softa.starter.message.mail.service.MailSendRecordService;
import io.softa.starter.message.mail.service.MailSendServerConfigService;
import io.softa.starter.message.mail.service.MailSendService;
import io.softa.starter.message.mail.service.MailTemplateService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MailApiControllerTest {

    private MailApiController controller;
    private MailSendService mailSendService;
    private MailSendRecordService sendRecordService;
    private MailSendServerConfigService sendConfigService;
    private MailTemplateService templateService;

    @BeforeEach
    void setUp() {
        controller = new MailApiController();
        mailSendService = mock(MailSendService.class);
        sendRecordService = mock(MailSendRecordService.class);
        sendConfigService = mock(MailSendServerConfigService.class);
        templateService = mock(MailTemplateService.class);

        ReflectionTestUtils.setField(controller, "mailSendService", mailSendService);
        ReflectionTestUtils.setField(controller, "sendRecordService", sendRecordService);
        ReflectionTestUtils.setField(controller, "sendConfigService", sendConfigService);
        ReflectionTestUtils.setField(controller, "templateService", templateService);
    }

    // ========== Status ==========

    @Test
    void getStatusReturnsDto() {
        MailSendRecord record = new MailSendRecord();
        record.setId(1L);
        record.setSubject("Test");
        record.setToAddresses("[\"user@example.com\"]");
        record.setStatus(MailSendStatus.SENT);
        record.setRetryCount(0);
        record.setSentAt(LocalDateTime.of(2026, 4, 10, 10, 0));
        record.setReadReceiptReceived(true);
        record.setReadReceiptReceivedAt(LocalDateTime.of(2026, 4, 10, 12, 0));
        record.setBounced(false);

        when(sendRecordService.getById(1L)).thenReturn(Optional.of(record));

        ApiResponse<MailSendStatusDTO> response = controller.getStatus(1L);
        Assertions.assertNotNull(response.getData());

        MailSendStatusDTO dto = response.getData();
        Assertions.assertEquals(1L, dto.getId());
        Assertions.assertEquals("Test", dto.getSubject());
        Assertions.assertEquals(MailSendStatus.SENT, dto.getStatus());
        Assertions.assertTrue(dto.getReadReceiptReceived());
        Assertions.assertFalse(dto.getBounced());
    }

    @Test
    void getStatusReturnsNullForNonexistent() {
        when(sendRecordService.getById(99L)).thenReturn(Optional.empty());

        ApiResponse<MailSendStatusDTO> response = controller.getStatus(99L);
        Assertions.assertNull(response.getData());
    }

    // ========== Senders ==========

    @Test
    void listSendersReturnsSummaries() {
        MailSendServerConfig config1 = new MailSendServerConfig();
        config1.setId(1L);
        config1.setName("Primary");
        config1.setFromAddress("primary@example.com");
        config1.setFromName("Primary Sender");
        config1.setIsDefault(true);
        config1.setIsEnabled(true);

        MailSendServerConfig config2 = new MailSendServerConfig();
        config2.setId(2L);
        config2.setName("Backup");
        config2.setFromAddress("backup@example.com");
        config2.setIsDefault(false);
        config2.setIsEnabled(true);

        when(sendConfigService.searchList(any(FlexQuery.class))).thenReturn(List.of(config1, config2));

        ApiResponse<List<MailSenderSummaryDTO>> response = controller.listSenders();
        Assertions.assertNotNull(response.getData());
        Assertions.assertEquals(2, response.getData().size());

        MailSenderSummaryDTO first = response.getData().getFirst();
        Assertions.assertEquals(1L, first.getId());
        Assertions.assertEquals("Primary", first.getName());
        Assertions.assertEquals("primary@example.com", first.getFromAddress());
        Assertions.assertTrue(first.getIsDefault());
    }

    @Test
    void listSendersReturnsEmptyWhenNoneEnabled() {
        when(sendConfigService.searchList(any(Filters.class))).thenReturn(List.of());

        ApiResponse<List<MailSenderSummaryDTO>> response = controller.listSenders();
        Assertions.assertNotNull(response.getData());
        Assertions.assertTrue(response.getData().isEmpty());
    }

    // ========== Templates ==========

    @Test
    void listTemplatesReturnsSummaries() {
        MailTemplate template = new MailTemplate();
        template.setId(1L);
        template.setCode("USER_WELCOME");
        template.setName("Welcome Email");
        template.setDescription("Sent to new users");
        template.setLanguage(Language.EN_US);
        template.setSubject("Welcome, {{ name }}!");
        template.setDefaultPriority("Normal");

        when(templateService.searchList(any(Filters.class))).thenReturn(List.of(template));

        ApiResponse<List<MailTemplateSummaryDTO>> response = controller.listTemplates();
        Assertions.assertNotNull(response.getData());
        Assertions.assertEquals(1, response.getData().size());

        MailTemplateSummaryDTO dto = response.getData().getFirst();
        Assertions.assertEquals("USER_WELCOME", dto.getCode());
        Assertions.assertEquals("Welcome Email", dto.getName());
        Assertions.assertEquals("en-US", dto.getLanguage().getCode());
    }

    // ========== Template Preview ==========

    @Test
    void previewTemplateRendersSubjectAndBody() {
        MailTemplate template = new MailTemplate();
        template.setCode("WELCOME");
        template.setSubject("Hello, {{ name }}!");
        template.setBody("<p>Welcome {{ name }}!</p>");

        when(templateService.resolve("WELCOME")).thenReturn(template);
        when(templateService.renderSubject(any(), any())).thenReturn("Hello, Alice!");
        when(templateService.renderBody(any(), any())).thenReturn("<p>Welcome Alice!</p>");

        MailTemplatePreviewDTO request = new MailTemplatePreviewDTO();
        request.setCode("WELCOME");
        request.setVariables(Map.of("name", "Alice"));

        ApiResponse<MailTemplatePreviewDTO> response = controller.previewTemplate(request);
        Assertions.assertNotNull(response.getData());
        Assertions.assertEquals("Hello, Alice!", response.getData().getRenderedSubject());
        Assertions.assertEquals("<p>Welcome Alice!</p>", response.getData().getRenderedBody());
    }

    @Test
    void previewTemplateHandlesNullVariables() {
        MailTemplate template = new MailTemplate();
        template.setCode("SIMPLE");

        when(templateService.resolve("SIMPLE")).thenReturn(template);
        when(templateService.renderSubject(any(), any())).thenReturn("Subject");
        when(templateService.renderBody(any(), any())).thenReturn("Body");

        MailTemplatePreviewDTO request = new MailTemplatePreviewDTO();
        request.setCode("SIMPLE");
        request.setVariables(null);

        ApiResponse<MailTemplatePreviewDTO> response = controller.previewTemplate(request);
        Assertions.assertNotNull(response.getData());
        Assertions.assertEquals("Subject", response.getData().getRenderedSubject());
    }

    // ========== Send by Template ==========

    @Test
    void sendByTemplateCallsService() {
        controller.sendByTemplate("WELCOME", List.of("user@example.com"),
                Map.of("name", "Bob"));

        verify(mailSendService).sendByTemplate("WELCOME", List.of("user@example.com"),
                Map.of("name", "Bob"));
    }

    @Test
    void sendByTemplateHandlesNullVariables() {
        controller.sendByTemplate("SIMPLE", List.of("user@example.com"), null);

        verify(mailSendService).sendByTemplate("SIMPLE", List.of("user@example.com"),
                java.util.Collections.emptyMap());
    }
}
