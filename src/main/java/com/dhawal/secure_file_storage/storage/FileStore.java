package com.dhawal.secure_file_storage.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dhawal.secure_file_storage.controlplane.QualityTier;

@Component
public class FileStore {

    private static final boolean POSIX =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final Path root;

    public FileStore(@Value("${vault.storage.root}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path stagingRoot() {
        return dir(root.resolve("staging"));
    }

    public Path staging(String uploadId) {
        return stagingRoot().resolve(checked(uploadId, Ids.UPLOAD.pattern()));
    }

    public Path chunk(String uploadId, int index) {
        return staging(uploadId).resolve(index + ".chunk");
    }

    public Path manifest(String uploadId) {
        return staging(uploadId).resolve("manifest.json");
    }

    public Path fileDir(String fileId) {
        return dir(dir(root.resolve("files")).resolve(checked(fileId, Ids.FILE.pattern())));
    }

    public Path blob(String fileId, QualityTier tier) {
        return fileDir(fileId).resolve(tier.wire() + ".bin");
    }

    public Path jobsRoot() {
        return dir(root.resolve("jobs"));
    }

    public Path job(String fileId) {
        return jobsRoot().resolve(checked(fileId, Ids.FILE.pattern()) + ".json");
    }

    public Path freshWorkDir(String fileId) {
        Path work = dir(root.resolve("work")).resolve(checked(fileId, Ids.FILE.pattern()));
        deleteTree(work);
        return dir(work);
    }

    public long usableSpace() {
        try {
            return Files.getFileStore(Files.exists(root) ? root : root.getParent()).getUsableSpace();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteTree(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path dir(Path path) {
        try {
            if (!Files.isDirectory(path)) {
                Files.createDirectories(path);
                if (POSIX) {
                    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
                }
            }
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String checked(String id, String pattern) {
        if (id == null || !id.matches(pattern)) {
            throw ApiException.notFound("That file doesn't exist.");
        }
        return id;
    }
}
