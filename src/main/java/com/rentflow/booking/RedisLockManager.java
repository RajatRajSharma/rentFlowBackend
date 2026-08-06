package com.rentflow.booking;

import com.rentflow.common.exception.LockAcquisitionException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis-backed {@link LockManager} using Redisson's {@link RLock}.
 *
 * Two timings matter, and they solve different problems:
 *
 *  - waitMs  — how long to queue for the lock before giving up. Bounds how long a user waits
 *              when many people fight for the same item.
 *  - leaseMs — how long Redis holds the lock if we never release it. Without a lease, an
 *              instance that crashes mid-booking would leave the item locked forever.
 *
 * leaseMs must comfortably exceed the worst-case time of the guarded work, or the lock could
 * expire while the transaction is still running and let a second writer in.
 */
@Component
public class RedisLockManager implements LockManager {

    private static final Logger log = LoggerFactory.getLogger(RedisLockManager.class);

    private final RedissonClient redisson;
    private final long waitMs;
    private final long leaseMs;

    public RedisLockManager(RedissonClient redisson,
                            @Value("${app.lock.wait-ms:3000}") long waitMs,
                            @Value("${app.lock.lease-ms:10000}") long leaseMs) {
        this.redisson = redisson;
        this.waitMs = waitMs;
        this.leaseMs = leaseMs;
    }

    @Override
    public <T> T withLock(String key, Supplier<T> action) {
        RLock lock = redisson.getLock("lock:" + key);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.debug("Could not acquire lock {} within {}ms", key, waitMs);
                throw new LockAcquisitionException(key);
            }
            return action.get();
        } catch (InterruptedException ex) {
            // Restore the flag so callers up the stack can still see the interruption.
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException(key);
        } finally {
            // isHeldByCurrentThread guards against unlocking a lock we never got, or one
            // whose lease already expired and was handed to someone else.
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
