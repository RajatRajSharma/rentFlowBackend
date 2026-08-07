package com.rentflow.payment;

import com.rentflow.common.lock.LockManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * "Do this exactly once per key, no matter how many times you're asked."
 *
 * Networks retry. A renter double-clicks Pay, a mobile client resends on a flaky
 * connection, a proxy replays a request it thinks timed out — and every one of those must
 * produce one charge, not three.
 *
 * <h3>Three layers, because each alone has a hole</h3>
 * <pre>
 *   1. Replay check      — already done? return the previous result. No lock, no work.
 *   2. Distributed lock  — concurrent retries of the same key queue instead of racing.
 *   3. UNIQUE constraint — the database refuses a duplicate even if 1 and 2 both fail.
 * </pre>
 *
 * Layer 1 alone loses the race: two simultaneous requests both find nothing and both
 * proceed. Layer 2 alone is useless the moment Redis is down. Layer 3 alone is correct
 * but returns an ugly error instead of the caller's original result. Together they give
 * the fast path, the concurrent path, and the last resort.
 *
 * This mirrors the booking engine's three-layer defence deliberately — same problem shape
 * (two writers, one permitted outcome), same answer.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final LockManager lockManager;

    public IdempotencyService(LockManager lockManager) {
        this.lockManager = lockManager;
    }

    /**
     * Run {@code action} once for {@code key}; on any repeat, return what {@code replay}
     * finds instead.
     *
     * @param key    the scope to serialise — everything that must not run concurrently
     *               shares one. Note this need not be the same thing {@code replay} looks
     *               up by: paying uses a booking-wide lock scope but a per-request-key
     *               replay, so competing attempts queue while each retry still gets back
     *               its own original result.
     * @param replay looks for the result of a previous run. Called twice by design: once
     *               on the fast path, and again inside the lock — because between those
     *               two moments another thread may have finished the work. Skipping the
     *               second check is the classic double-checked-locking bug.
     * @param action the work, run at most once.
     */
    public <T> T once(String key, Supplier<Optional<T>> replay, Supplier<T> action) {
        // Fast path: the overwhelmingly common retry, answered without touching Redis.
        Optional<T> alreadyDone = replay.get();
        if (alreadyDone.isPresent()) {
            log.debug("Idempotent replay for key {}", key);
            return alreadyDone.get();
        }

        return lockManager.withLock("idem:" + key, () ->
                // Re-check under the lock: whoever held it before us may have just done it.
                replay.get().orElseGet(action));
    }
}
