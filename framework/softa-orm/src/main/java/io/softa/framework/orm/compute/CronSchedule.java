package io.softa.framework.orm.compute;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.cronutils.model.Cron;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

/**
 * What a cron expression schedules, read the way the cron scheduler reads it: {@link CronUtils#CRON_FORMAT} (Quartz),
 * its times in the server's zone. Six or seven fields: second, minute, hour, day of month, month, day of week
 * ({@code 1}–{@code 7} = Sunday–Saturday, or {@code SUN}–{@code SAT}), an optional year; one of the two day fields is
 * {@code ?}.
 *
 * <p>Kept apart from {@link CronUtils}, whose public static methods are imported into the expression engine.
 */
public final class CronSchedule {

    /** The most runs a preview lists. */
    public static final int MAX_PREVIEW_RUNS = 10;

    private static final CronParser PARSER =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronUtils.CRON_FORMAT));

    private CronSchedule() {
    }

    /**
     * The expression as the scheduler reads it.
     *
     * @throws IllegalArgumentException with the parser's reason, when the scheduler cannot run the expression
     */
    public static Cron parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("The cron expression is empty");
        }
        Cron cron = PARSER.parse(expression);
        cron.validate();
        return cron;
    }

    /**
     * The next {@code count} times {@code expression} fires after {@code after}: fewer when its schedule ends (a year
     * field), none when it never fires again.
     *
     * @throws IllegalArgumentException with the parser's reason, when the scheduler cannot run the expression
     */
    public static List<ZonedDateTime> nextRuns(String expression, ZonedDateTime after, int count) {
        ExecutionTime executionTime = ExecutionTime.forCron(parse(expression));
        List<ZonedDateTime> runs = new ArrayList<>(count);
        ZonedDateTime previous = after;
        while (runs.size() < count) {
            Optional<ZonedDateTime> next = executionTime.nextExecution(previous);
            if (next.isEmpty()) {
                break;
            }
            previous = next.get();
            runs.add(previous);
        }
        return runs;
    }

    /**
     * Why {@code expression} schedules nothing — the scheduler cannot run it, or it never fires again — or
     * {@code null} when it does.
     */
    public static String refusal(String expression) {
        try {
            return nextRuns(expression, ZonedDateTime.now(), 1).isEmpty() ? "it never fires again" : null;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /**
     * What an editor shows beside a cron expression: whether it is accepted, what it means in words, and when it
     * fires next, in the server's zone — where the scheduler runs it.
     *
     * @param count how many runs to list, at most {@link #MAX_PREVIEW_RUNS}
     */
    public static CronPreview preview(String expression, int count) {
        String refusal = refusal(expression);
        if (refusal != null) {
            return CronPreview.refused(refusal);
        }
        ZoneId zone = ZoneId.systemDefault();
        List<ZonedDateTime> runs = nextRuns(expression, ZonedDateTime.now(zone), Math.clamp(count, 1, MAX_PREVIEW_RUNS));
        return new CronPreview(true, null, CronUtils.cronSemantic(expression), zone.getId(),
                runs.stream().map(ZonedDateTime::toOffsetDateTime).toList());
    }
}
