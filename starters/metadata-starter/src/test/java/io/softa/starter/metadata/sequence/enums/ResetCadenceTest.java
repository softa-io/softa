package io.softa.starter.metadata.sequence.enums;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResetCadenceTest {

    private static final LocalDateTime SAMPLE = LocalDateTime.of(2026, 4, 29, 14, 30, 15);

    @Test
    void none_yieldsEmptyKey() {
        // NONE keeps current_key as the empty string (not null) so the
        // SQL `<=>` comparison stays in the equality branch on subsequent calls.
        assertThat(ResetCadence.NONE.computeKey(SAMPLE)).isEmpty();
    }

    @Test
    void yearly_yields4DigitYear() {
        assertThat(ResetCadence.YEARLY.computeKey(SAMPLE)).isEqualTo("2026");
    }

    @Test
    void monthly_yieldsYearMonth() {
        assertThat(ResetCadence.MONTHLY.computeKey(SAMPLE)).isEqualTo("2026-04");
    }

    @Test
    void daily_yieldsYearMonthDay() {
        assertThat(ResetCadence.DAILY.computeKey(SAMPLE)).isEqualTo("2026-04-29");
    }

    @Test
    void monthlyAndDaily_zeroPadShortMonth() {
        // Defensive check: SimpleDateFormat-style "MM" / "dd" patterns must zero-pad
        // single-digit months / days, otherwise lastResetKey comparisons could miss.
        LocalDateTime jan2 = LocalDateTime.of(2026, 1, 2, 0, 0);
        assertThat(ResetCadence.MONTHLY.computeKey(jan2)).isEqualTo("2026-01");
        assertThat(ResetCadence.DAILY.computeKey(jan2)).isEqualTo("2026-01-02");
    }
}
