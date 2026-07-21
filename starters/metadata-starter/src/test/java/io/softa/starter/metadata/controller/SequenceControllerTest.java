package io.softa.starter.metadata.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import io.softa.framework.orm.sequence.SequenceService;
import io.softa.starter.metadata.entity.SysSequence;
import io.softa.starter.metadata.enums.SequenceMode;
import io.softa.starter.metadata.service.SysSequenceService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HTTP allocation gate: a NO_GAP sequence cannot keep its no-gap promise
 * across an HTTP boundary (the counter commits with the response, the
 * caller's business write can still fail), so {@code next}/{@code nextBatch}
 * reject it with guidance instead of silently downgrading to ALLOW_GAP
 * semantics. ALLOW_GAP passes through.
 */
class SequenceControllerTest {

    private final SequenceService sequenceService = Mockito.mock(SequenceService.class);
    private final SysSequenceService sysSequenceService = Mockito.mock(SysSequenceService.class);
    private final SequenceController controller = new SequenceController(sequenceService, sysSequenceService);

    private void stubMode(String code, SequenceMode mode) {
        SysSequence config = new SysSequence();
        config.setCode(code);
        config.setMode(mode);
        when(sysSequenceService.loadConfigByCode(code)).thenReturn(config);
    }

    @Test
    void allowGapSequence_isServedOverHttp() {
        stubMode("Audit.eventNo", SequenceMode.ALLOW_GAP);
        when(sequenceService.next("Audit.eventNo")).thenReturn("EVT-00001");

        assertEquals("EVT-00001", controller.next("Audit.eventNo").getData());
    }

    @Test
    void noGapSequence_isRejectedOverHttp_withGuidance() {
        stubMode("Employee.code", SequenceMode.NO_GAP);

        RuntimeException e = assertThrows(RuntimeException.class, () -> controller.next("Employee.code"));
        assertTrue(e.getMessage().contains("NO_GAP"));
        assertTrue(e.getMessage().contains("business transaction"));
        verify(sequenceService, never()).next(Mockito.anyString());
    }

    @Test
    void noGapSequence_isRejectedOnBatchToo() {
        stubMode("Employee.code", SequenceMode.NO_GAP);

        assertThrows(RuntimeException.class, () -> controller.nextBatch("Employee.code", 5));
        verify(sequenceService, never()).nextBatch(Mockito.anyString(), Mockito.anyInt());
    }

    @Test
    void batchCount_staysBounded() {
        stubMode("Audit.eventNo", SequenceMode.ALLOW_GAP);
        when(sequenceService.nextBatch("Audit.eventNo", 100)).thenReturn(List.of());

        assertDoesNotThrow(() -> controller.nextBatch("Audit.eventNo", 100));
        assertThrows(RuntimeException.class, () -> controller.nextBatch("Audit.eventNo", 101));
    }
}
