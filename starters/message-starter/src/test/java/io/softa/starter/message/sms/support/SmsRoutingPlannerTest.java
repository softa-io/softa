package io.softa.starter.message.sms.support;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsTemplate;
import io.softa.starter.message.sms.entity.SmsTemplateProviderBinding;
import io.softa.starter.message.sms.enums.SmsProvider;
import io.softa.starter.message.sms.service.SmsTemplateProviderBindingService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmsRoutingPlannerTest {

    private SmsRoutingPlanner planner;
    private SmsProviderDispatcher dispatcher;
    private SmsTemplateProviderBindingService bindingService;

    @BeforeEach
    void setUp() {
        dispatcher = mock(SmsProviderDispatcher.class);
        bindingService = mock(SmsTemplateProviderBindingService.class);
        planner = new SmsRoutingPlanner(dispatcher, bindingService);
    }

    @Test
    void templatePlanIntersectsCountryEligibleProvidersWithBindings() {
        SendSmsDTO dto = templateDto("+8613800138000");
        SmsProviderConfig twilio = provider(1L, SmsProvider.TWILIO, 10);
        SmsProviderConfig aliyun = provider(2L, SmsProvider.ALIYUN, 20);
        SmsTemplate template = template(10L);

        when(dispatcher.resolveRegion("+8613800138000")).thenReturn("CN");
        when(dispatcher.resolveProviders("+8613800138000")).thenReturn(List.of(twilio, aliyun));
        when(bindingService.findByTemplateId(10L)).thenReturn(List.of(
                binding(10L, 1L, "", "tw_generic", "Twilio", 10),
                binding(11L, 2L, "CN", "ali_cn", "Aliyun", 1)));

        SmsRoutingPlanner.Plan plan = planner.plan(routingRequest(dto, template));

        assertEquals(2L, plan.providerConfig().getId());
        assertEquals("ali_cn", plan.externalTemplateId());
        assertEquals("Aliyun", plan.signName());
        assertEquals("CN", plan.regionCode());
    }

    @Test
    void regionSpecificBindingWinsOverGenericForSameProvider() {
        SendSmsDTO dto = templateDto("+8613800138000");
        SmsProviderConfig aliyun = provider(2L, SmsProvider.ALIYUN, 20);
        SmsTemplate template = template(10L);

        when(dispatcher.resolveRegion("+8613800138000")).thenReturn("CN");
        when(dispatcher.resolveProviders("+8613800138000")).thenReturn(List.of(aliyun));
        when(bindingService.findByTemplateId(10L)).thenReturn(List.of(
                binding(10L, 2L, "", "generic", "Generic", 0),
                binding(11L, 2L, "CN", "cn_specific", "CN", 50)));

        SmsRoutingPlanner.Plan plan = planner.plan(routingRequest(dto, template));

        assertEquals("cn_specific", plan.externalTemplateId());
        assertEquals("CN", plan.signName());
    }

    @Test
    void noBindingsUsesFirstEligibleProviderAndDtoOverrides() {
        SendSmsDTO dto = templateDto("+14155550100");
        SmsProviderConfig twilio = provider(1L, SmsProvider.TWILIO, 10);
        SmsTemplate template = template(10L);

        when(dispatcher.resolveRegion("+14155550100")).thenReturn("US");
        when(dispatcher.resolveProviders("+14155550100")).thenReturn(List.of(twilio));
        when(bindingService.findByTemplateId(10L)).thenReturn(List.of());
        when(bindingService.findPlatformBindingsByTemplateId(10L)).thenReturn(List.of());

        SmsRoutingPlanner.Plan plan = planner.plan(routingRequest(dto, template));

        assertEquals(1L, plan.providerConfig().getId());
        assertEquals("dtoTpl", plan.externalTemplateId());
        assertEquals("dtoSign", plan.signName());
    }

    @Test
    void bindingConfiguredButNoneEligibleThrows() {
        SendSmsDTO dto = templateDto("+14155550100");
        SmsProviderConfig twilio = provider(1L, SmsProvider.TWILIO, 10);
        SmsTemplate template = template(10L);

        when(dispatcher.resolveRegion("+14155550100")).thenReturn("US");
        when(dispatcher.resolveProviders("+14155550100")).thenReturn(List.of(twilio));
        when(bindingService.findByTemplateId(10L)).thenReturn(List.of(
                binding(10L, 2L, "US", "other", "Other", 1)));

        assertThrows(BusinessException.class, () -> planner.plan(routingRequest(dto, template)));
    }

    @Test
    void directPlanUsesPhoneNumberFromRequest() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+14155550100");
        dto.setExternalTemplateId("directTpl");
        dto.setSignName("directSign");
        SmsProviderConfig twilio = provider(1L, SmsProvider.TWILIO, 10);

        when(dispatcher.resolveRegion("+14155550100")).thenReturn("US");
        when(dispatcher.resolveProviders("+14155550100")).thenReturn(List.of(twilio));

        SmsRoutingPlanner.Plan plan = planner.plan(routingRequest(dto, null));

        assertEquals(1L, plan.providerConfig().getId());
        assertEquals("directTpl", plan.externalTemplateId());
        assertEquals("directSign", plan.signName());
        assertEquals("US", plan.regionCode());
        verifyNoInteractions(bindingService);
    }

    private static SendSmsDTO templateDto(String phoneNumber) {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber(phoneNumber);
        dto.setTemplateCode("VERIFY");
        dto.setExternalTemplateId("dtoTpl");
        dto.setSignName("dtoSign");
        return dto;
    }

    private static SmsRoutingPlanner.RoutingRequest routingRequest(
            SendSmsDTO dto, SmsTemplate template) {
        return new SmsRoutingPlanner.RoutingRequest(
                dto.getPhoneNumber(), dto.getProviderConfigId(), dto.getTemplateCode(),
                dto.getExternalTemplateId(), dto.getSignName(), template);
    }

    private static SmsTemplate template(Long id) {
        SmsTemplate template = new SmsTemplate();
        template.setId(id);
        template.setCode("VERIFY");
        template.setContent("Your code is {{ code }}");
        return template;
    }

    private static SmsProviderConfig provider(Long id, SmsProvider type, int priority) {
        SmsProviderConfig config = new SmsProviderConfig();
        config.setId(id);
        config.setProviderType(type);
        config.setPriority(priority);
        config.setIsEnabled(true);
        return config;
    }

    private static SmsTemplateProviderBinding binding(Long id, Long providerId, String region,
                                                      String externalTemplateId, String signName,
                                                      int priority) {
        SmsTemplateProviderBinding binding = new SmsTemplateProviderBinding();
        binding.setId(id);
        binding.setTemplateId(10L);
        binding.setProviderConfigId(providerId);
        binding.setRegionCode(region);
        binding.setExternalTemplateId(externalTemplateId);
        binding.setSignName(signName);
        binding.setPriority(priority);
        binding.setIsEnabled(true);
        return binding;
    }
}
