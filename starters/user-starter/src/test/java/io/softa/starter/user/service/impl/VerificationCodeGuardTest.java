package io.softa.starter.user.service.impl;

import java.util.HashMap;
import java.util.Objects;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.orm.service.CacheService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The five abuse limits from PRD D2, one test each — plus the two rules that only show up in
 * combination and are the easy ones to get wrong:
 *
 * <ul>
 *   <li>requesting a fresh code must NOT clear the consecutive-failure counter, or
 *       "resend" becomes the way to reset a ban;</li>
 *   <li>the ban must gate SENDING too, for the same reason.</li>
 * </ul>
 *
 * <p>Backed by an in-memory cache rather than a mock with per-call stubs: these rules are about
 * counters accumulating across calls, and stubbing each read separately would assert the test's
 * idea of the sequence rather than the guard's behaviour.
 */
class VerificationCodeGuardTest {

    private final Map<String, String> store = new HashMap<>();
    private final Map<String, Long> counters = new HashMap<>();
    private final CacheService cache = mock(CacheService.class);
    private final VerificationCodeGuard guard = new VerificationCodeGuard(cache);

    VerificationCodeGuardTest() {
        when(cache.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        doAnswer(inv -> store.put(inv.getArgument(0), Objects.toString(inv.getArgument(1), null)))
                .when(cache).save(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
        doAnswer(inv -> {
            String k = inv.getArgument(0);
            store.remove(k);
            counters.remove(k);
            return null;
        }).when(cache).clear(anyString());
        when(cache.increment(anyString(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            long next = counters.getOrDefault(k, 0L) + 1;
            counters.put(k, next);
            return next;
        });
    }

    private static final String ID = "+8613800138000";

    /** Send a code, bypassing the 60s cooldown so multi-send tests stay about their own rule. */
    private String issueIgnoringCooldown(String code) {
        store.remove("verification-code:cooldown:" + ID);
        guard.beforeSend(ID);
        guard.store(ID, code);
        return code;
    }

    // ─────────────────────── sending ───────────────────────

    @Test
    void secondSendWithinTheMinute_isRefused() {
        guard.beforeSend(ID);

        assertThatThrownBy(() -> guard.beforeSend(ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many requests");
    }

    @Test
    void eleventhSendInADay_isRefused() {
        for (int i = 0; i < 10; i++) {
            store.remove("verification-code:cooldown:" + ID);
            guard.beforeSend(ID);
        }
        store.remove("verification-code:cooldown:" + ID);

        assertThatThrownBy(() -> guard.beforeSend(ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Too many requests");
    }

    // ─────────────────────── verifying ───────────────────────

    @Test
    void correctCode_passes_andIsConsumed() {
        issueIgnoringCooldown("123456");

        assertThatCode(() -> guard.verify(ID, "123456")).doesNotThrowAnyException();

        // Consumed: the same code cannot be replayed.
        assertThatThrownBy(() -> guard.verify(ID, "123456"))
                .hasMessageContaining("expired");
    }

    @Test
    void missingCode_isRefused_withoutSpendingAnAttempt() {
        issueIgnoringCooldown("123456");
        Map<String, Long> countersBefore = new HashMap<>(counters);

        for (int i = 0; i < 12; i++) {
            String missing = i % 2 == 0 ? null : " ";
            assertThatThrownBy(() -> guard.verify(ID, missing))
                    .hasMessageContaining("enter the verification code");
        }
        // Twelve empty tries moved no counter: not the code's budget, not the ban.
        assertThat(counters).isEqualTo(countersBefore);
        assertThatCode(() -> guard.verify(ID, "123456")).doesNotThrowAnyException();
    }

    @Test
    void wrongCode_saysTryAgain_untilTheFifth_whichKillsTheCode() {
        issueIgnoringCooldown("123456");

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> guard.verify(ID, "000000"))
                    .hasMessageContaining("Incorrect code");
        }
        // The 5th exhausts this code's budget — different message, different remedy.
        assertThatThrownBy(() -> guard.verify(ID, "000000"))
                .hasMessageContaining("no longer valid");
        // And the right code no longer works: the code itself is gone.
        assertThatThrownBy(() -> guard.verify(ID, "123456"))
                .hasMessageContaining("expired");
    }

    @Test
    void tenConsecutiveFailures_banTheChannel() {
        // Spread across three codes: the per-code limit alone would never reach ten, which is
        // exactly why the consecutive counter exists.
        for (int round = 0; round < 3; round++) {
            issueIgnoringCooldown("123456");
            for (int i = 0; i < 4; i++) {
                try {
                    guard.verify(ID, "000000");
                } catch (BusinessException expected) {
                    // counting failures
                }
            }
        }
        // 12 failures total — the ban fires at 10.
        assertThatThrownBy(() -> guard.verify(ID, "123456"))
                .hasMessageContaining("temporarily unavailable");
    }

    @Test
    void aBannedChannel_cannotRequestANewCode() {
        // Otherwise "request a new code" would be the way out of a ban.
        store.put("verification-code:ban:" + ID, "1");

        assertThatThrownBy(() -> guard.beforeSend(ID))
                .hasMessageContaining("temporarily unavailable");
    }

    @Test
    void requestingAFreshCode_doesNotResetTheConsecutiveCounter() {
        issueIgnoringCooldown("123456");
        for (int i = 0; i < 3; i++) {
            try {
                guard.verify(ID, "000000");
            } catch (BusinessException expected) {
                // counting failures
            }
        }
        long before = counters.get("verification-code:consecutive:" + ID);

        issueIgnoringCooldown("654321");

        assertThat(counters.get("verification-code:consecutive:" + ID)).isEqualTo(before);
    }

    @Test
    void success_clearsTheConsecutiveCounter() {
        // The counter exists to catch a RUN of failures; a success ends the run.
        issueIgnoringCooldown("123456");
        try {
            guard.verify(ID, "000000");
        } catch (BusinessException expected) {
            // one failure
        }

        guard.verify(ID, "123456");

        assertThat(counters).doesNotContainKey("verification-code:consecutive:" + ID);
    }

    @Test
    void expiredOrNeverSent_saysExpired() {
        assertThatThrownBy(() -> guard.verify(ID, "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
    }
}
