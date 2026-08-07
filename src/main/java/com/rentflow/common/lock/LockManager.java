package com.rentflow.common.lock;

import java.util.function.Supplier;

/**
 * A mutual-exclusion lock that works ACROSS application instances.
 *
 * Why not {@code synchronized}: a JVM monitor only locks one process. The moment you run two
 * instances behind a load balancer it guarantees nothing, and the double-booking it was meant
 * to prevent comes straight back. A distributed lock lives outside the JVM, so every instance
 * respects it.
 *
 * An interface rather than a concrete class so the implementation is swappable (Redis today,
 * something else tomorrow) and so tests can substitute a no-op.
 */
public interface LockManager {

    /**
     * Run {@code action} while holding the lock named {@code key}, then release it.
     *
     * @throws com.rentflow.common.exception.LockAcquisitionException if the lock could not be
     *         acquired within the configured wait time
     */
    <T> T withLock(String key, Supplier<T> action);
}
