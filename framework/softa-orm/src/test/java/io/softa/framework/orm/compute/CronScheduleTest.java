package io.softa.framework.orm.compute;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A cron expression is read one way — the scheduler's — and previewed the way the scheduler would run it. */
class CronScheduleTest {

    @Test
    void theNextRunsFollowOneAnother() {
        ZonedDateTime friday = ZonedDateTime.of(2026, 10, 30, 10, 0, 0, 0, ZoneId.of("UTC"));

        List<ZonedDateTime> runs = CronSchedule.nextRuns("0 0 9 ? * MON-FRI", friday, 3);

        assertEquals(List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY),
                runs.stream().map(ZonedDateTime::getDayOfWeek).toList());
        assertTrue(runs.stream().allMatch(run -> run.getHour() == 9));
    }

    @Test
    void theDayOfWeekCountsFromSunday() {
        ZonedDateTime friday = ZonedDateTime.of(2026, 10, 30, 10, 0, 0, 0, ZoneId.of("UTC"));

        List<ZonedDateTime> runs = CronSchedule.nextRuns("0 0 9 ? * 2,7", friday, 2);

        assertEquals(List.of(DayOfWeek.SATURDAY, DayOfWeek.MONDAY),
                runs.stream().map(ZonedDateTime::getDayOfWeek).toList());
    }

    @Test
    void aScheduleThatEndsListsFewerRuns() {
        ZonedDateTime before = ZonedDateTime.of(2099, 1, 1, 0, 0, 0, 0, ZoneId.of("UTC"));

        assertEquals(1, CronSchedule.nextRuns("0 0 2 1 1 ? 2099", before, 5).size());
    }

    @Test
    void theRefusalSaysWhyItSchedulesNothing() {
        assertNull(CronSchedule.refusal("0 */5 * * * ?"));
        assertNull(CronSchedule.refusal("*/10 * * * * ?"), "the scheduler runs every few seconds");
        assertEquals("it never fires again", CronSchedule.refusal("0 0 2 1 1 ? 2020"));
        assertNotNull(CronSchedule.refusal("0 9 * * 1"), "five fields");
        assertNotNull(CronSchedule.refusal("0 0 9 * * MON"), "both day fields");
        assertNotNull(CronSchedule.refusal("0 0 9 * * *"), "both day fields");
        assertNotNull(CronSchedule.refusal(" "));
        assertNotNull(CronSchedule.refusal(null));
    }

    @Test
    void aPreviewShowsTheMeaningAndTheNextRunsOrWhyNot() {
        CronPreview valid = CronSchedule.preview("0 0 9 ? * MON-FRI", 50);

        assertTrue(valid.valid());
        assertNull(valid.message());
        assertNotNull(valid.description());
        assertEquals(CronSchedule.MAX_PREVIEW_RUNS, valid.nextRuns().size(), "clamped");
        assertEquals(ZoneId.systemDefault().getId(), valid.zone());

        CronPreview invalid = CronSchedule.preview("0 9 * * 1", 5);
        assertFalse(invalid.valid());
        assertNotNull(invalid.message());
        assertNull(invalid.description());
        assertTrue(invalid.nextRuns().isEmpty());
        assertEquals(ZoneId.systemDefault().getId(), invalid.zone());
    }
}
