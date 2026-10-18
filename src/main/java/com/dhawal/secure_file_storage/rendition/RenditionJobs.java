package com.dhawal.secure_file_storage.rendition;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.dhawal.secure_file_storage.controlplane.FileCipher;
import com.dhawal.secure_file_storage.controlplane.QualityTier;
import com.dhawal.secure_file_storage.controlplane.Rendition;
import com.dhawal.secure_file_storage.controlplane.StoredFile;
import com.dhawal.secure_file_storage.storage.ControlPlane;
import com.dhawal.secure_file_storage.storage.FileStore;
import com.dhawal.secure_file_storage.storage.Ids;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RenditionJobs {

    private static final Logger log = LoggerFactory.getLogger(RenditionJobs.class);

    private static final int MAX_ATTEMPTS = 3;

    record Job(String fileId, int attempts) {
    }

    private final FileStore store;
    private final ControlPlane controlPlane;
    private final RenditionTools tools;
    private final JsonMapper json;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "renditions");
        thread.setDaemon(true);
        return thread;
    });

    public RenditionJobs(FileStore store, ControlPlane controlPlane, RenditionTools tools, JsonMapper json) {
        this.store = store;
        this.controlPlane = controlPlane;
        this.tools = tools;
        this.json = json;
    }

    public void submit(String fileId) throws IOException {
        write(new Job(fileId, 0));
        worker.execute(() -> run(fileId));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void resumeUnfinished() {
        if (!controlPlane.ready() || !Files.isDirectory(store.root().resolve("jobs"))) {
            return;
        }
        try (DirectoryStream<Path> jobs = Files.newDirectoryStream(store.jobsRoot(), "*.json")) {
            for (Path job : jobs) {
                String fileId = job.getFileName().toString().replace(".json", "");
                if (Ids.FILE.matcher(fileId).matches()) {
                    worker.execute(() -> run(fileId));
                }
            }
        } catch (IOException e) {
            log.error("Could not read unfinished rendition jobs", e);
        }
    }

    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        Future<?> marker = worker.submit(() -> {
        });
        try {
            marker.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @PreDestroy
    public void stop() {
        worker.shutdownNow();
    }

    void run(String fileId) {
        Path marker = store.job(fileId);
        try {
            if (!controlPlane.ready() || !Files.exists(marker)) {
                return;
            }

            Job job = json.readValue(Files.readString(marker), Job.class);
            if (job.attempts() >= MAX_ATTEMPTS) {
                log.error("Giving up on renditions for {} after {} attempts", fileId, job.attempts());
                Files.deleteIfExists(marker);
                return;
            }
            write(new Job(fileId, job.attempts() + 1));

            Optional<StoredFile> found = controlPlane.catalog().find(fileId);
            if (found.isEmpty()) {
                Files.deleteIfExists(marker);
                return;
            }

            StoredFile file = found.get();
            FileCipher cipher = controlPlane.cipher();
            Path work = store.freshWorkDir(fileId);
            try {
                Path source = work.resolve("source.bin");
                Rendition original = file.renditions().get(QualityTier.ORIGINAL);
                try (SeekableByteChannel channel = Files.newByteChannel(store.blob(fileId, QualityTier.ORIGINAL));
                        InputStream plain = cipher.decrypt(original.keyHandle(), channel, 0);
                        OutputStream out = Files.newOutputStream(source)) {
                    plain.transferTo(out);
                }
                tools.render(file.kind(), source, work, output -> keep(file, output, cipher));
            } finally {
                FileStore.deleteTree(work);
            }

            Files.deleteIfExists(marker);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Renditions for {} failed", fileId, e);
        }
    }

    private void keep(StoredFile file, RenditionTools.Output output, FileCipher cipher) throws IOException {
        String handle = Ids.keyHandle(file.id(), output.tier());
        Path target = store.blob(file.id(), output.tier());
        Path tmp = target.resolveSibling(output.tier().wire() + ".bin.tmp");

        try (OutputStream out = cipher.encrypt(handle, Files.newOutputStream(tmp))) {
            Files.copy(output.path(), out);
        }
        FileStore.moveIntoPlace(tmp, target);

        long size = Files.size(output.path());
        Files.delete(output.path());
        controlPlane.catalog().addRendition(file.id(), new Rendition(output.tier(), output.mime(), size, handle));
    }

    private void write(Job job) throws IOException {
        Path marker = store.job(job.fileId());
        Path tmp = marker.resolveSibling(job.fileId() + ".json.tmp");
        Files.writeString(tmp, json.writeValueAsString(job));
        FileStore.moveIntoPlace(tmp, marker);
    }
}
