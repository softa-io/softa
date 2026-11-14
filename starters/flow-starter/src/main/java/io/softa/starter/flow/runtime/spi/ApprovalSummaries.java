package io.softa.starter.flow.runtime.spi;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.softa.framework.base.annotation.OptionItem;

/**
 * The pieces a {@link ModelApprovalHandler} words its {@link ApprovalSubmission#summary()} from, written one way in
 * every approval list: parts joined with " · ", a date as {@code 12 Nov 2026}, a range as
 * {@code 12 Nov 2026 – 14 Nov 2026}, a quantity without trailing zeros and with its unit ({@code 1.5 days}), an amount
 * with its currency ({@code SGD 1,234.50}). English, as the lists are.
 */
public final class ApprovalSummaries {

    /** What separates the parts of a summary. */
    public static final String SEPARATOR = " · ";

    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private ApprovalSummaries() {
    }

    /** The parts that say something — null and blank ones left out — joined; null when none does. */
    public static String join(Object... parts) {
        String joined = Stream.of(parts)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::strip)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.joining(SEPARATOR));
        return joined.isEmpty() ? null : joined;
    }

    /** {@code 12 Nov 2026}; null for none. */
    public static String date(LocalDate date) {
        return date == null ? null : DAY_MONTH_YEAR.format(date);
    }

    /** {@code 12 Nov 2026 – 14 Nov 2026}; one day, or one end alone, is that date. */
    public static String dates(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.equals(end)) {
            return date(start != null ? start : end);
        }
        return date(start) + " – " + date(end);
    }

    /** {@code 18:00–22:00}, or the one time known; null for neither. */
    public static String times(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            return start == null && end == null ? null : HOUR_MINUTE.format(start != null ? start : end);
        }
        return HOUR_MINUTE.format(start) + "–" + HOUR_MINUTE.format(end);
    }

    /**
     * An option as the lists show it: its {@link OptionItem} label, else its constant in words
     * ({@code TIME_OFF_IN_LIEU} → {@code Time off in lieu}); null for none.
     */
    public static String label(Enum<?> option) {
        if (option == null) {
            return null;
        }
        try {
            OptionItem item = option.getDeclaringClass().getField(option.name()).getAnnotation(OptionItem.class);
            if (item != null && !item.label().isBlank()) {
                return item.label();
            }
        } catch (NoSuchFieldException e) {
            // An enum constant is always a field; the words below answer anyway.
        }
        String words = option.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** {@code 1 day}, {@code 1.5 days}: the number without trailing zeros, the unit by it; null for no number. */
    public static String quantity(BigDecimal value, String one, String many) {
        if (value == null) {
            return null;
        }
        BigDecimal number = value.stripTrailingZeros();
        String text = number.scale() <= 0 ? number.toBigInteger().toString() : number.toPlainString();
        return text + " " + (number.compareTo(BigDecimal.ONE) == 0 ? one : many);
    }

    /** {@code SGD 1,234.50}: two decimals and thousands separators, the currency first when known; null for none. */
    public static String amount(BigDecimal value, String currency) {
        if (value == null) {
            return null;
        }
        String number = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH)).format(value);
        return currency == null || currency.isBlank() ? number : currency.strip() + " " + number;
    }
}
