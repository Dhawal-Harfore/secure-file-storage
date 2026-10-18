package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RateLimiterTests {

    private static final class SteppingClock extends Clock {

        private Instant now = Instant.parse("2026-10-18T12:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final SteppingClock clock = new SteppingClock();
    private final RateLimiter limiter = new RateLimiter(3, Duration.ofMinutes(15), clock);

    @Test
    void allowsUpToTheLimitThenRefuses() {
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isFalse();
    }

    @Test
    void keysAreCountedSeparately() {
        for (int i = 0; i < 3; i++) {
            limiter.allow("a");
        }

        assertThat(limiter.allow("a")).isFalse();
        assertThat(limiter.allow("b")).isTrue();
    }

    @Test
    void windowSlides() {
        limiter.allow("a");
        clock.advance(Duration.ofMinutes(10));
        limiter.allow("a");
        limiter.allow("a");

        assertThat(limiter.allow("a")).isFalse();

        clock.advance(Duration.ofMinutes(5));
        assertThat(limiter.allow("a")).isTrue();
        assertThat(limiter.allow("a")).isFalse();
    }

    @Test
    void refusedAttemptsDoNotExtendTheBlock() {
        for (int i = 0; i < 3; i++) {
            limiter.allow("a");
        }
        for (int i = 0; i < 50; i++) {
            limiter.allow("a");
        }

        clock.advance(Duration.ofMinutes(15));
        assertThat(limiter.allow("a")).isTrue();
    }
}
