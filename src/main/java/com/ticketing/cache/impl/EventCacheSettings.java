package com.ticketing.cache.impl;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the in-memory event cache. Instantiated by the composition root ({@code config}) from
 * {@code ticketing.cache.*}.
 *
 * @param ttl     time-to-live of a cached event after it is written (short: counters change constantly)
 * @param maxSize maximum number of cached events
 */
public record EventCacheSettings(Duration ttl, long maxSize) {

    /**
     * Validates a positive TTL and size.
     */
    public EventCacheSettings {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be at least 1");
        }
    }
}
