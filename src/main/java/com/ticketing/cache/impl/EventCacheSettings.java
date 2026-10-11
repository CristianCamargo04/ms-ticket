package com.ticketing.cache.impl;

import java.time.Duration;
import java.util.Objects;

public record EventCacheSettings(Duration ttl, long maxSize) {
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
