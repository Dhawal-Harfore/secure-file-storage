package com.dhawal.secure_file_storage.rendition;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

final class ProcessRunner {

    static final String PATH = "/usr/local/bin:/usr/bin:/bin:/opt/homebrew/bin";

    record Result(int exitCode, boolean timedOut, String errorTail) {

        boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    private ProcessRunner() {
    }

    static Result run(List<String> command, Path workDir, Duration timeout, int niceness)
            throws IOException, InterruptedException {

        List<String> full = new ArrayList<>();
        if (niceness > 0) {
            full.add("nice");
            full.add("-n");
            full.add(String.valueOf(niceness));
        }
        full.addAll(command);

        Path log = Files.createTempFile(workDir, "tool-", ".log");
        ProcessBuilder builder = new ProcessBuilder(full)
                .directory(workDir.toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()));

        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", PATH);
        env.put("LANG", "C");

        Process process = builder.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                return new Result(-1, true, tail(log));
            }
            return new Result(process.exitValue(), false, tail(log));
        } catch (InterruptedException e) {
            kill(process);
            throw e;
        } finally {
            Files.deleteIfExists(log);
        }
    }

    private static void kill(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }

    private static String tail(Path log) throws IOException {
        String text = Files.readString(log, StandardCharsets.UTF_8);
        return text.length() > 600 ? text.substring(text.length() - 600) : text;
    }
}
