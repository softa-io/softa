package io.softa.starter.flow.runtime.engine;

import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.compute.CronPreview;
import io.softa.framework.orm.compute.CronSchedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A flow's cron expressions are read one way — the scheduler's, {@link CronSchedule} — and previewed the way publishing
 * judges them: the scheduler's rules and one of the flow's own, at most once a minute.
 */
class FlowCronTest {

    @Test
    void theRefusalSaysWhyPublishingRefusesIt() {
        assertNull(FlowCron.refusal("0 */5 * * * ?"));
        assertEquals("it never fires again", FlowCron.refusal("0 0 2 1 1 ? 2020"));
        assertNotNull(FlowCron.refusal("0 9 * * 1"));
        assertNotNull(FlowCron.refusal(" "));
    }

    @Test
    void aFlowRunsAtMostOnceAMinute() {
        assertNull(FlowCron.refusal("0 * * * * ?"));
        assertNull(FlowCron.refusal("30 15 9 ? * MON-FRI"));
        for (String everyFewSeconds : List.of("* * * * * ?", "0/10 * * * * ?", "0,30 * * * * ?", "0-5 * * * * ?")) {
            String refusal = FlowCron.refusal(everyFewSeconds);
            assertNotNull(refusal, everyFewSeconds);
            assertTrue(refusal.contains("more than once a minute"), refusal);
            assertNull(CronSchedule.refusal(everyFewSeconds), "the scheduler alone runs " + everyFewSeconds);
        }
        CronPreview everyFiveSeconds = FlowCron.preview("*/5 * * * * ?", 3);
        assertFalse(everyFiveSeconds.valid());
        assertTrue(everyFiveSeconds.message().contains("more than once a minute"));
    }

    @Test
    void aPreviewShowsTheMeaningAndTheNextRunsOrWhyNot() {
        CronPreview valid = FlowCron.preview("0 0 9 ? * MON-FRI", 50);

        assertTrue(valid.valid());
        assertNotNull(valid.description());
        assertEquals(CronSchedule.MAX_PREVIEW_RUNS, valid.nextRuns().size(), "clamped");
        assertEquals(ZoneId.systemDefault().getId(), valid.zone());

        CronPreview invalid = FlowCron.preview("0 9 * * 1", 5);
        assertFalse(invalid.valid());
        assertNotNull(invalid.message());
        assertTrue(invalid.nextRuns().isEmpty());
    }
}
