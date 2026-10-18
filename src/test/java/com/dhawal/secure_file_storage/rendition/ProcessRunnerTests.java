package com.dhawal.secure_file_storage.rendition;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessRunnerTests {

    @TempDir
    Path work;

    @Test
    void toolsSeeOnlyAMinimalEnvironment() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(List.of("sh", "-c", "env"), work, Duration.ofSeconds(10), 0);

        assertThat(result.ok()).isTrue();
        assertThat(result.errorTail()).contains("PATH=" + ProcessRunner.PATH).contains("LANG=C");
        assertThat(result.errorTail()).doesNotContain("RESEND").doesNotContain("HOME=").doesNotContain("USER=");
    }

    @Test
    void slowToolsAreKilledAtTheTimeout() throws Exception {
        long start = System.nanoTime();
        ProcessRunner.Result result = ProcessRunner.run(List.of("sleep", "30"), work, Duration.ofMillis(300), 10);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.ok()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void failuresReportTheirExitCode() throws Exception {
        ProcessRunner.Result result = ProcessRunner.run(List.of("sh", "-c", "echo broken >&2; exit 3"),
                work, Duration.ofSeconds(10), 0);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.errorTail()).contains("broken");
    }
}
