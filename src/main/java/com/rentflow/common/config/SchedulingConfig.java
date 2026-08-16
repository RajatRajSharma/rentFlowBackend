package com.rentflow.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the clock behind the @Scheduled workers. Switched off with
 * {@code app.workers.enabled=false} so a sweep can't fire mid-assertion in a test.
 *
 * The workers stay ordinary beans either way — the tests call them directly, which is the
 * only way to assert on a sweep without waiting for one.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.workers.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
