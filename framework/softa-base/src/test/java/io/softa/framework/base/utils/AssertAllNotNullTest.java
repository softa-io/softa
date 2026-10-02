package io.softa.framework.base.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.IllegalArgumentException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Assert#allNotNull} over the collections callers actually hand it.
 *
 * <p>The immutable cases are the point. {@code contains(null)} is how this check used to be
 * written, and an immutable collection answers that question by throwing NullPointerException
 * whatever it holds — so a perfectly valid {@code List.of(row)} failed the assertion meant to
 * protect it, as an NPE raised from inside the assertion rather than the message it exists to
 * produce. The framework hands itself exactly that collection: the import pipeline's row-by-row
 * fallback persists {@code List.of(row)}, so every ONLY_CREATE template failed there — and the NPE
 * masked whatever the batch attempt had failed on, leaving the importer with "an unexpected error"
 * and the real cause nowhere.
 */
class AssertAllNotNullTest {

    @Test
    void anImmutableCollectionWithNoNullsPasses() {
        assertThatCode(() -> Assert.allNotNull(List.of("a"), "must not be empty: {0}", "x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> Assert.allNotNull(List.of("a", "b"), "must not be empty: {0}", "x"))
                .doesNotThrowAnyException();
        // Three or more is a different implementation class from List.of's one- and two-element
        // forms, and only those two reject contains(null) by throwing.
        assertThatCode(() -> Assert.allNotNull(List.of("a", "b", "c"), "must not be empty: {0}", "x"))
                .doesNotThrowAnyException();
    }

    @Test
    void aMutableCollectionHoldingANullIsStillRefused() {
        List<String> withNull = new ArrayList<>();
        withNull.add("a");
        withNull.add(null);

        assertThatThrownBy(() -> Assert.allNotNull(withNull, "must not be empty: {0}", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAndEmptyAreRefused() {
        assertThatThrownBy(() -> Assert.allNotNull((List<?>) null, "must not be empty: {0}", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Assert.allNotNull(List.of(), "must not be empty: {0}", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theArrayOverloadBehavesTheSameWay() {
        assertThatCode(() -> Assert.allNotNull(new Object[] {"a"}, "must not be empty: {0}", "x"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> Assert.allNotNull(new Object[] {"a", null}, "must not be empty: {0}", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        // Arrays.asList tolerated contains(null), so the array overload was never broken the way
        // the collection one was; pinned so a later tidy-up does not make them consistent the
        // wrong way round.
        assertThatCode(() -> Assert.allNotNull(Arrays.asList("a", "b").toArray(), "m: {0}", "x"))
                .doesNotThrowAnyException();
    }
}
