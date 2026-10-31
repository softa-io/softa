package io.softa.starter.flow.runtime.engine;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.dto.FlowCronPreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A flow's cron expressions are read one way — the scheduler's — and previewed the way publishing judges them. */
class FlowCronTest {

    @Test
    void theNextRunsFollowOneAnother() {
        ZonedDateTime friday = ZonedDateTime.of(2026, 10, 30, 10, 0, 0, 0, ZoneId.of("UTC"));

        List<ZonedDateTime> runs = FlowCron.nextRuns("0 0 9 ? * MON-FRI", friday, 3);

        assertEquals(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
                runs.stream().map(ZonedDateTime::getDayOfWeek).toList());
        assertTrue(runs.stream().allMatch(run -> run.getHour() == 9));
    }

    @Test
    void aScheduleThatEndsListsFewerRuns() {
        ZonedDateTime before = ZonedDateTime.of(2099, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));

        assertEquals(1, FlowCron.nextRuns("0 0 2 1 1 ? 2099", before, 5).size());
    }

    @Test
    void theRefusalSaysWhyPublishingRefusesIt() {
        assertNull(FlowCron.refusal("0 */5 * * * ?"));
        assertEquals("it never fires again", FlowCron.refusal("0 0 2 1 1 ? 2020"));
        assertNotNull(FlowCron.refusal("0 9 * * 1"));
        assertNotNull(FlowCron.refusal(" "));
    }

    @Test
    void aPreviewShowsTheMeaningAndTheNextRunsOrWhyNot() {
        FlowCronPreview valid = FlowCron.preview("0 0 9 ? * MON-FRI", 50);

        assertTrue(valid.valid());
        assertNotNull(valid.description());
        assertEquals(FlowCron.MAX_PREVIEW_RUNS, valid.nextRuns().size(), "clamped");
        assertEquals(ZoneId.systemDefault().getId(), valid.zone());

        FlowCronPreview invalid = FlowCron.preview("0 9 * * 1", 5);
        assertFalse(invalid.valid());
        assertNotNull(invalid.message());
        assertTrue(invalid.nextRuns().isEmpty());
    }
}
