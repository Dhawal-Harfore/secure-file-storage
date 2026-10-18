package com.dhawal.secure_file_storage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimiter {

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();

    public RateLimiter(int limit, Duration window) {
        this(limit, window, Clock.systemUTC());
    }

    public RateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    public boolean allow(String key) {

        Instant now = clock.instant();

        if (calls.incrementAndGet() % 256 == 0) {
            hits.entrySet().removeIf(entry -> {
                synchronized (entry.getValue()) {
                    prune(entry.getValue(), now);
                    return entry.getValue().isEmpty();
                }
            });
        }

        Deque<Instant> recent = hits.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (recent) {
            prune(recent, now);

            if (recent.size() >= limit) {
                return false;
            }

            recent.addLast(now);
            return true;
        }
    }

    private void prune(Deque<Instant> recent, Instant now) {
        Instant cutoff = now.minus(window);

        while (!recent.isEmpty() && !recent.peekFirst().isAfter(cutoff)) {
            recent.pollFirst();
        }
    }
}
