package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class MagicLinkServiceTests {

    private final MagicLinkService service = new MagicLinkService();

    @Test
    void tokenReturnsTheEmailItWasIssuedFor() {
        String token = service.createToken("asha@example.com");

        assertThat(service.verifyToken(token)).isEqualTo("asha@example.com");
    }

    @Test
    void secondUseIsRefused() {
        String token = service.createToken("asha@example.com");
        service.verifyToken(token);

        assertThatThrownBy(() -> service.verifyToken(token))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownTokenIsRefused() {
        assertThatThrownBy(() -> service.verifyToken("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentUsesLetExactlyOneThrough() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);

        try {
            for (int round = 0; round < 200; round++) {
                String token = service.createToken("race@example.com");
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> attempts = new ArrayList<>();

                for (int i = 0; i < 16; i++) {
                    attempts.add(pool.submit(() -> {
                        start.await();
                        try {
                            service.verifyToken(token);
                            return true;
                        } catch (IllegalStateException e) {
                            return false;
                        }
                    }));
                }

                start.countDown();

                int accepted = 0;
                for (Future<Boolean> attempt : attempts) {
                    if (attempt.get()) {
                        accepted++;
                    }
                }

                assertThat(accepted).as("round %d", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
