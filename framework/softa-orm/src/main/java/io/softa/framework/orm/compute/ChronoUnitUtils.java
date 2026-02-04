package io.softa.framework.orm.compute;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import io.softa.framework.orm.utils.MapUtils;

/**
 * ChronoUnit utility class.
 * Providing methods for importing ChronoUnit enum items.
 * <p>
 * Include: ChronoUnit.YEARS, ChronoUnit.MONTHS, ChronoUnit.DAYS,
 *          ChronoUnit.HOURS, ChronoUnit.MINUTES, ChronoUnit.SECONDS,
 *          ChronoUnit.MILLIS, ChronoUnit.MiCROS, ChronoUnit.NANOS...
 * <p>
 * Example:
 *     between(ChronoUnit.DAYS, dateTime1, LocalDateTime.now())
 */
public class ChronoUnitUtils {
    public static final Map<String, Object> CHRONO_UNIT_ENV = initChronoUnitEnv();

    private static Map<String, Object> initChronoUnitEnv() {
        Map<String, Object> chronoValues = new HashMap<>();
        ChronoUnit[] values = ChronoUnit.values();
        for (ChronoUnit value : values) {
            chronoValues.put(value.name(), value);
        }
        return MapUtils.of("ChronoUnit", chronoValues);
    }
}
