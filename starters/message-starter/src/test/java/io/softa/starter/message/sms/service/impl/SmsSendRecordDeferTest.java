package io.softa.starter.message.sms.service.impl;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.message.sms.entity.SmsSendRecord;
import io.softa.starter.message.sms.enums.SmsSendStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SmsSendRecordDeferTest {

    @Test
    @SuppressWarnings("unchecked")
    void aDeferredRecordIsDueAgainWithItsRetriesUnspent() {
        SmsSendRecordServiceImpl service = Mockito.spy(new SmsSendRecordServiceImpl());
        ModelService<Long> modelService = mock(ModelService.class);
        ReflectionTestUtils.setField(service, "modelService", modelService);
        SmsSendRecord stored = new SmsSendRecord();
        stored.setId(7L);
        stored.setRetryCount(2);
        doReturn(Optional.of(stored)).when(service).getById(7L);
        when(modelService.updateOne(anyString(), any())).thenReturn(true);
        LocalDateTime at = LocalDateTime.now().plusMinutes(1);

        assertThat(service.markDeferred(7L, 3L, "SEND_LIMIT_MINUTE_EXCEEDED", "deferred", at)).isTrue();

        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        verify(modelService).updateOne(anyString(), patch.capture());
        assertThat(patch.getValue())
                .containsEntry("status", SmsSendStatus.RETRY)
                .containsEntry("retryCount", 2)
                .containsEntry("nextRetryAt", at)
                .containsEntry("errorCode", "SEND_LIMIT_MINUTE_EXCEEDED");
    }
}
