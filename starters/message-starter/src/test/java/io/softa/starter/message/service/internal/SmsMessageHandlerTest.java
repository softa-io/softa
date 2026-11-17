package io.softa.starter.message.service.internal;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.message.MessageScope;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.message.shared.TenantScopes;
import io.softa.starter.message.sms.dto.SendSmsDTO;
import io.softa.starter.message.sms.entity.SmsProviderConfig;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsSendStatus;
import io.softa.starter.message.sms.service.SmsSendRecordService;
import io.softa.starter.message.sms.support.SmsProviderDispatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Acceptance contract for one SMS message: only the caller's mistakes are rejected, and the request is
 * stored as it was made — templates, routing and quota wait for the first delivery attempt
 * ({@code SmsDeliveryProcessorTest}).
 */
class SmsMessageHandlerTest {

    private SmsSendRecordService recordService;
    private SmsProviderDispatcher providerDispatcher;
    private SmsMessageHandler handler;

    @BeforeEach
    void setUp() {
        recordService = mock(SmsSendRecordService.class);
        providerDispatcher = mock(SmsProviderDispatcher.class);
        handler = new SmsMessageHandler(recordService, providerDispatcher);
        when(recordService.createOne(any(SmsSendRecord.class))).thenReturn(55L);
    }

    @Test
    void directContentIsStoredAsAPendingRecord() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+6591234567");
        dto.setContent("hi");

        Long id = handler.send(dto);

        assertEquals(55L, id);
        SmsSendRecord record = capturedRecord();
        assertEquals(SmsSendStatus.PENDING, record.getStatus());
        assertEquals("+6591234567", record.getPhoneNumber());
        assertEquals("hi", record.getContent());
        // Fixed only when the record is prepared.
        assertNull(record.getProviderType());
    }

    @Test
    void aTemplateRequestIsStoredUnrenderedWithItsScope() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+6591234567");
        dto.setTemplateCode("user.verification-code");
        dto.setTemplateVariables(Map.of("code", "123456"));
        dto.setScope(MessageScope.PLATFORM);

        handler.send(dto);

        SmsSendRecord record = capturedRecord();
        assertEquals("user.verification-code", record.getTemplateCode());
        assertEquals(Map.of("code", "123456"), JsonUtils.jsonNodeToMap(record.getTemplateVariables()));
        assertNull(record.getContent());
        assertTrue(record.getPlatformScope());
    }

    @Test
    void missingPhoneIsRejectedBeforePersisting() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setContent("hi");

        assertThrows(BusinessException.class, () -> handler.send(dto));
        verifyNoInteractions(recordService);
    }

    @Test
    void aNumberWithoutItsCountryCodeIsRejectedBeforePersisting() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("91234567");
        dto.setContent("hi");

        assertThrows(BusinessException.class, () -> handler.send(dto));
        verifyNoInteractions(recordService);
    }

    @Test
    void aNumberWithLettersOrTheWrongLengthIsRejectedBeforePersisting() {
        for (String typed : List.of("+65 9123 4567 abc", "+65 912 3456")) {
            SendSmsDTO dto = new SendSmsDTO();
            dto.setPhoneNumber(typed);
            dto.setContent("hi");

            assertThrows(BusinessException.class, () -> handler.send(dto), typed);
        }
        verifyNoInteractions(recordService);
    }

    @Test
    void aNumberWhoseCountryCannotBePlacedIsRejectedBeforePersisting() {
        doThrow(new BusinessException("Unable to determine the country of phone number +441000000000"))
                .when(providerDispatcher).resolveRegion("+441000000000");
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+44 1000 000000");
        dto.setContent("hi");

        assertThrows(BusinessException.class, () -> handler.send(dto));
        verifyNoInteractions(recordService);
    }

    @Test
    void theNumberIsStoredInE164WhateverItWasTypedWith() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber(" +65 9123-4567 ");
        dto.setContent("hi");

        handler.send(dto);

        assertEquals("+6591234567", capturedRecord().getPhoneNumber());
        verify(providerDispatcher).resolveRegion("+6591234567");
    }

    @Test
    void canSendAnswersFromTheNumberAndTheRoutes() {
        when(providerDispatcher.resolveProviders("+6591234567")).thenReturn(List.of(new SmsProviderConfig()));
        when(providerDispatcher.resolveProviders("+60123456789"))
                .thenThrow(new BusinessException("No SMS provider serves region MY"));

        assertTrue(handler.canSend("+65 9123 4567"));
        assertFalse(handler.canSend("+60 12-345 6789"));
        assertFalse(handler.canSend("91234567"));
        assertFalse(handler.canSend(null));
    }

    @Test
    void missingContentAndTemplateIsRejectedBeforePersisting() {
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+6591234567");

        assertThrows(BusinessException.class, () -> handler.send(dto));
        verifyNoInteractions(recordService);
    }

    @Test
    void aBatchIsValidatedWholeBeforeAnythingIsWritten() {
        SendSmsDTO good = new SendSmsDTO();
        good.setPhoneNumber("+6591234567");
        good.setContent("hi");
        SendSmsDTO bad = new SendSmsDTO();
        bad.setContent("no number");

        assertThrows(BusinessException.class, () -> handler.sendBatch(List.of(good, bad)));
        verify(recordService, never()).createOne(any(SmsSendRecord.class));
    }

    @Test
    void aPlatformSendIsFiledUnderTheTenantItIsMadeFor() {
        // The table is not multi-tenant: the owner is written at acceptance, and it is the tenant the
        // send is made for whatever the scope — a code sent while one of tenant 7's users is signed in
        // is tenant 7's, and counts against its quota.
        Context tenantRequest = new Context("trace-1");
        tenantRequest.setTenantId(7L);
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+6591234567");
        dto.setTemplateCode("user.verification-code");
        dto.setScope(MessageScope.PLATFORM);

        ContextHolder.runWith(tenantRequest, () -> handler.send(dto));

        SmsSendRecord record = capturedRecord();
        assertEquals(7L, record.getTenantId());
        assertTrue(record.getPlatformScope());
    }

    @Test
    void aPlatformSendWithNoTenantIsFiledUnderThePlatform() {
        // A login code for someone in several companies: no session, no company to name — the record
        // still needs an owner.
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+6591234567");
        dto.setTemplateCode("user.verification-code");
        dto.setScope(MessageScope.PLATFORM);

        ContextHolder.runWith(new Context("trace-1"), () -> handler.send(dto));

        assertEquals(TenantScopes.PLATFORM, capturedRecord().getTenantId());
    }

    @Test
    void aTenantSendIsFiledUnderTheSendingTenantEvenFromACrossTenantJob() {
        Context job = new Context("trace-1");
        job.setTenantId(7L);
        job.setCrossTenant(true);
        SendSmsDTO dto = new SendSmsDTO();
        dto.setPhoneNumber("+6591234567");
        dto.setContent("hi");

        ContextHolder.runWith(job, () -> handler.send(dto));

        SmsSendRecord record = capturedRecord();
        assertEquals(7L, record.getTenantId());
        assertFalse(record.getPlatformScope());
    }

    private SmsSendRecord capturedRecord() {
        ArgumentCaptor<SmsSendRecord> captor = ArgumentCaptor.forClass(SmsSendRecord.class);
        verify(recordService).createOne(captor.capture());
        return captor.getValue();
    }
}
