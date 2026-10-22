package com.dhawal.secure_file_storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:magic-links;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MagicLinkServiceTests {

    @Autowired
    private MagicLinkService service;

    @Autowired
    private MagicLinkTokenRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void tokenReturnsTheEmailItWasIssuedFor() {
        String token = service.createToken("asha@example.com");

        assertThat(service.verifyToken(token)).isEqualTo("asha@example.com");
    }

    @Test
    void onlyTheHashIsStored() {
        String token = service.createToken("hash@example.com");

        List<String> stored = jdbc.queryForList("select token_hash from magic_link_tokens", String.class);
        assertThat(stored).noneMatch(h -> h.equals(token));
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
    void expiredTokenIsRefused() {
        String token = service.createToken("late@example.com");
        jdbc.update("update magic_link_tokens set expires_at = ? where email = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), "late@example.com");

        assertThatThrownBy(() -> service.verifyToken(token))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void linksSurviveARestart() {
        String token = service.createToken("restart@example.com");

        MagicLinkService afterRestart = new MagicLinkService(repository);

        String email = new TransactionTemplate(transactions).execute(status -> afterRestart.verifyToken(token));
        assertThat(email).isEqualTo("restart@example.com");
    }

    @Test
    void concurrentUsesLetExactlyOneThrough() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);

        try {
            for (int round = 0; round < 40; round++) {
                String token = service.createToken("race@example.com");
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> attempts = new ArrayList<>();

                for (int i = 0; i < 8; i++) {
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
