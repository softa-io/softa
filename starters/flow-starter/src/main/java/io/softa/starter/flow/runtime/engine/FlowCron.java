package io.softa.starter.flow.runtime.engine;

import com.cronutils.model.field.CronFieldName;
import com.cronutils.model.field.expression.On;

import io.softa.framework.orm.compute.CronPreview;
import io.softa.framework.orm.compute.CronSchedule;

/**
 * A flow's cron dialect — a Cron trigger's schedule — is the cron scheduler's, read
 * by {@link CronSchedule}: Quartz, its times in the server's zone. Six or seven fields: second, minute, hour, day of
 * month, month, day of week ({@code 1}–{@code 7} = Sunday–Saturday, or {@code SUN}–{@code SAT}), an optional year; one
 * of the two day fields is {@code ?}.
 *
 * <p>A flow runs at most once a minute: the second field is a single second ({@code 0}, say). Every second, or every
 * few, would start an instance each time — a cost, not a schedule anyone needs. That is the one rule a flow adds to
 * the scheduler's.
 */
public final class FlowCron {

    private static final String TOO_OFTEN = "it fires more than once a minute; a flow runs at most once a minute: "
            + "give the second field a single value, such as 0";

    private FlowCron() {
    }

    /**
     * Why publishing refuses {@code expression} — the scheduler cannot run it, it fires more than once a minute, or it
     * never fires again — or {@code null} when it does not.
     */
    public static String refusal(String expression) {
        return firesMoreThanOnceAMinute(expression) ? TOO_OFTEN : CronSchedule.refusal(expression);
    }

    /**
     * What the designer shows beside a cron expression: whether publishing accepts it, what it means in words, and
     * when it fires next, in the server's zone — where the scheduler runs it.
     *
     * @param count how many runs to list, at most {@link CronSchedule#MAX_PREVIEW_RUNS}
     */
    public static CronPreview preview(String expression, int count) {
        return firesMoreThanOnceAMinute(expression)
                ? CronPreview.refused(TOO_OFTEN)
                : CronSchedule.preview(expression, count);
    }

    /** Whether the second field is anything but a single second; false for an expression the scheduler cannot read. */
    private static boolean firesMoreThanOnceAMinute(String expression) {
        try {
            return !(CronSchedule.parse(expression).retrieve(CronFieldName.SECOND).getExpression() instanceof On);
        } catch (IllegalArgumentException e) {
            // CronSchedule says why it cannot be read
            return false;
        }
    }
}
