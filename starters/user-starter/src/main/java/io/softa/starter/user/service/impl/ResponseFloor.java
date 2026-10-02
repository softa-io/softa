package io.softa.starter.user.service.impl;

/**
 * Holds a pre-sign-in response to a fixed floor, so how long it took says nothing about what was
 * found.
 *
 * <p>Both entry points that answer "we have sent something, if there was anywhere to send it" do
 * markedly less work when there was not: no code to generate, no token to mint, no message to
 * publish. Left alone, a stopwatch answers the question the response itself refuses to — the same
 * oracle, measured instead of read — and that oracle over an unauthenticated endpoint returns a
 * verified roster of the people an organisation employs.
 *
 * <p>A floor rather than imitation work: the work there would be to imitate is sending, and sending
 * to an address nobody holds is the one thing these paths must not do.
 *
 * <p>It narrows the gap rather than closing it. A real send that overruns the floor still returns
 * late, so the residue is whatever delivery adds beyond it — which is why the floor is set well
 * clear of a normal send rather than trimmed to it. Closing the gap completely would mean answering
 * before the work is done. The floor doubles as a throttle on how fast an attacker can ask at all.
 */
final class ResponseFloor {

    /**
     * Comfortably above a normal send, comfortably below what a person would read as a stall.
     *
     * <p>Above, because a floor only hides what it sits on top of.
     */
    static final long MILLIS = 600L;

    private ResponseFloor() {
        // utility class — no instances
    }

    /**
     * Run {@code work}, then wait out whatever is left of the floor.
     *
     * <p>In a {@code finally}: a path that fails fast would otherwise be the quickest of all, and
     * the exception still travels once the floor is served.
     */
    static void hold(Runnable work) {
        long startedAt = System.nanoTime();
        try {
            work.run();
        } finally {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
            long remaining = MILLIS - elapsedMillis;
            if (remaining > 0) {
                try {
                    Thread.sleep(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
