package io.softa.starter.flow.runtime.spi;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The pieces of an approval's summary line, written one way in every list. */
class ApprovalSummariesTest {

    @Test
    void partsThatSaySomethingAreJoined() {
        assertEquals("Annual Leave · 3 days", ApprovalSummaries.join(null, " ", "Annual Leave", "3 days"));
        assertNull(ApprovalSummaries.join(null, ""));
    }

    @Test
    void aStretchOfDaysNamesBothEndsInFull() {
        LocalDate nov12 = LocalDate.of(2026, 11, 12);
        assertEquals("12 Nov 2026 – 14 Nov 2026", ApprovalSummaries.dates(nov12, LocalDate.of(2026, 11, 14)));
        assertEquals("12 Nov 2026", ApprovalSummaries.dates(nov12, nov12));
        assertEquals("12 Nov 2026", ApprovalSummaries.dates(nov12, null));
    }

    @Test
    void quantitiesAndAmountsReadAsPeopleWriteThem() {
        assertEquals("1 day", ApprovalSummaries.quantity(new BigDecimal("1.00"), "day", "days"));
        assertEquals("1.5 days", ApprovalSummaries.quantity(new BigDecimal("1.50"), "day", "days"));
        assertEquals("3 days", ApprovalSummaries.quantity(new BigDecimal("3.000"), "day", "days"));
        assertEquals("SGD 1,234.50", ApprovalSummaries.amount(new BigDecimal("1234.5"), "SGD"));
        assertEquals("80.00", ApprovalSummaries.amount(new BigDecimal("80"), null));
    }

    enum Compensation {
        @io.softa.framework.base.annotation.OptionItem(label = "Monetary Payment") MONETARY_PAYMENT,
        TIME_OFF_IN_LIEU
    }

    @Test
    void timesAndOptionsReadAsTheListsShowThem() {
        assertEquals("18:00–22:00", ApprovalSummaries.times(java.time.LocalTime.of(18, 0), java.time.LocalTime.of(22, 0)));
        assertEquals("Monetary Payment", ApprovalSummaries.label(Compensation.MONETARY_PAYMENT));
        assertEquals("Time off in lieu", ApprovalSummaries.label(Compensation.TIME_OFF_IN_LIEU));
        assertNull(ApprovalSummaries.label(null));
    }

    @Test
    void aLongSummaryIsCutNeverRefused() {
        String summary = new ApprovalSubmission("t", null, null, null, "x".repeat(300)).summary();
        assertEquals(ApprovalSubmission.SUMMARY_MAX_LENGTH, summary.length());
        assertNull(new ApprovalSubmission("t", null, null, null, "  ").summary());
    }
}
