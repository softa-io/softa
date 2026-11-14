package io.softa.starter.message.shared;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.softa.starter.message.mail.service.impl.MailSendRecordServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * A failure's code and message are cut to their columns before they are written: a longer one failed
 * the very write that records the failure, and the record stayed in its last state.
 */
class ErrorTextTest {

    @Test
    void textWithinTheLimitIsKeptAsItIs() {
        assertNull(ErrorText.clip(null, 10));
        String exact = "x".repeat(10);
        assertSame(exact, ErrorText.clip(exact, 10));
    }

    @Test
    void longerTextIsCutToTheLimitWithAnEllipsis() {
        String clipped = ErrorText.clip("y".repeat(5000), 1000);

        assertEquals(1000, clipped.length());
        assertTrue(clipped.endsWith("…"));
    }

    @Test
    void aSurrogatePairIsNotSplit() {
        // 8 ASCII characters, then an emoji (two chars) that the cut would split in half.
        String text = "abcdefgh😀tail";

        String clipped = ErrorText.clip(text, 10);

        assertEquals("abcdefgh…", clipped);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailureTransitionWritesTheClippedCodeAndMessage() {
        MailSendRecordServiceImpl service = spy(new MailSendRecordServiceImpl());
        ArgumentCaptor<Map<String, Object>> patch = ArgumentCaptor.forClass(Map.class);
        doReturn(true).when(service).updateVersioned(patch.capture());

        service.markFailed(7L, 3L, "C".repeat(150), "m".repeat(5000));

        assertEquals(ErrorText.CODE_LENGTH, ((String) patch.getValue().get("errorCode")).length());
        assertEquals(ErrorText.MESSAGE_LENGTH, ((String) patch.getValue().get("errorMessage")).length());
    }
}
