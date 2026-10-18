package com.dhawal.secure_file_storage.rendition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.dhawal.secure_file_storage.controlplane.FileKind;
import com.dhawal.secure_file_storage.controlplane.QualityTier;

@Component
public class RenditionTools {

    private static final Logger log = LoggerFactory.getLogger(RenditionTools.class);

    static final String VIDEO_FORMATS = "mov,matroska,avi,mpegts,flv,ogg,asf,mpeg,mpegvideo,h264,hevc,m4v";
    static final String IMAGE_FORMATS = "image2,png_pipe,jpeg_pipe,webp_pipe,bmp_pipe,tiff_pipe,gif,gif_pipe,heif";

    private static final Duration STILL_TIMEOUT = Duration.ofMinutes(2);

    public record Output(QualityTier tier, Path path, String mime) {
    }

    public interface Sink {
        void accept(Output output) throws IOException;
    }

    private final String ffmpeg;
    private final String ghostscript;
    private final int niceness;
    private final Duration videoTimeout;
    private final Duration documentTimeout;

    public RenditionTools(
            @Value("${vault.renditions.ffmpeg}") String ffmpeg,
            @Value("${vault.renditions.ghostscript}") String ghostscript,
            @Value("${vault.renditions.nice}") int niceness,
            @Value("${vault.renditions.video-timeout}") Duration videoTimeout,
            @Value("${vault.renditions.document-timeout}") Duration documentTimeout) {
        this.ffmpeg = ffmpeg;
        this.ghostscript = ghostscript;
        this.niceness = niceness;
        this.videoTimeout = videoTimeout;
        this.documentTimeout = documentTimeout;
    }

    public void render(FileKind kind, Path source, Path work, Sink sink)
            throws IOException, InterruptedException {

        switch (kind) {
            case VIDEO -> {
                Path poster = work.resolve("lowest.jpg");
                if (!attempt(QualityTier.LOWEST, "image/jpeg", poster, videoPoster(source, poster, true), work, STILL_TIMEOUT, sink)) {
                    attempt(QualityTier.LOWEST, "image/jpeg", poster, videoPoster(source, poster, false), work, STILL_TIMEOUT, sink);
                }
                Path reduced = work.resolve("reduced.webm");
                attempt(QualityTier.REDUCED, "video/webm", reduced, videoReduced(source, reduced), work, videoTimeout, sink);
            }
            case IMAGE -> {
                Path lowest = work.resolve("lowest.jpg");
                attempt(QualityTier.LOWEST, "image/jpeg", lowest, imageStill(source, lowest, 480, 8), work, STILL_TIMEOUT, sink);
                Path reduced = work.resolve("reduced.jpg");
                attempt(QualityTier.REDUCED, "image/jpeg", reduced, imageStill(source, reduced, 1280, 4), work, STILL_TIMEOUT, sink);
            }
            case PDF -> {
                Path lowest = work.resolve("lowest.jpg");
                attempt(QualityTier.LOWEST, "image/jpeg", lowest, pdfFirstPage(source, lowest), work, STILL_TIMEOUT, sink);
                Path reduced = work.resolve("reduced.pdf");
                attempt(QualityTier.REDUCED, "application/pdf", reduced, pdfReduced(source, reduced), work, documentTimeout, sink);
            }
            case OTHER -> {
            }
        }
    }

    private boolean attempt(QualityTier tier, String mime, Path out, List<String> command,
            Path work, Duration timeout, Sink sink) throws IOException, InterruptedException {

        Files.deleteIfExists(out);
        ProcessRunner.Result result = ProcessRunner.run(command, work, timeout, niceness);

        if (result.ok() && Files.isRegularFile(out) && Files.size(out) > 0) {
            sink.accept(new Output(tier, out, mime));
            return true;
        }

        log.warn("{} rendition failed ({}): {}", tier.wire(),
                result.timedOut() ? "timed out after " + timeout : "exit " + result.exitCode(),
                result.errorTail().strip());
        Files.deleteIfExists(out);
        return false;
    }

    List<String> videoPoster(Path source, Path out, boolean skipFirstSecond) {
        List<String> command = ffmpegInput(VIDEO_FORMATS);
        if (skipFirstSecond) {
            command.addAll(List.of("-ss", "1"));
        }
        command.addAll(List.of("-i", source.toString(),
                "-frames:v", "1", "-map_metadata", "-1",
                "-vf", "scale='min(480,iw)':-2",
                "-q:v", "5", "-f", "image2", "-y", out.toString()));
        return command;
    }

    List<String> videoReduced(Path source, Path out) {
        List<String> command = ffmpegInput(VIDEO_FORMATS);
        command.addAll(List.of("-i", source.toString(),
                "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-map_metadata", "-1",
                "-vf", "scale='min(720,iw)':'min(540,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0", "-deadline", "good", "-cpu-used", "4", "-row-mt", "1",
                "-c:a", "libopus", "-b:a", "96k",
                "-f", "webm", "-y", out.toString()));
        return command;
    }

    List<String> imageStill(Path source, Path out, int box, int quality) {
        List<String> command = ffmpegInput(IMAGE_FORMATS);
        command.addAll(List.of("-i", source.toString(),
                "-frames:v", "1", "-map_metadata", "-1",
                "-filter_complex", "[0:v]scale='min(" + box + ",iw)':'min(" + box + ",ih)'"
                        + ":force_original_aspect_ratio=decrease:force_divisible_by=2,format=rgba,split[a][b];"
                        + "[a]drawbox=c=white:t=fill[bg];[bg][b]overlay=format=auto,format=yuvj420p",
                "-q:v", String.valueOf(quality), "-f", "image2", "-y", out.toString()));
        return command;
    }

    List<String> pdfFirstPage(Path source, Path out) {
        return List.of(ghostscript, "-q", "-dSAFER", "-dBATCH", "-dNOPAUSE",
                "-sDEVICE=jpeg", "-r50", "-dJPEGQ=70", "-dFirstPage=1", "-dLastPage=1",
                "-sOutputFile=" + out, source.toString());
    }

    List<String> pdfReduced(Path source, Path out) {
        return List.of(ghostscript, "-q", "-dSAFER", "-dBATCH", "-dNOPAUSE",
                "-sDEVICE=pdfimage24", "-r72",
                "-sOutputFile=" + out, source.toString());
    }

    private List<String> ffmpegInput(String formats) {
        return new ArrayList<>(List.of(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
                "-threads", "1", "-protocol_whitelist", "file", "-format_whitelist", formats));
    }
}
