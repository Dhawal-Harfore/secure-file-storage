package com.dhawal.secure_file_storage.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dhawal.secure_file_storage.controlplane.StoredFile;
import com.dhawal.secure_file_storage.controlplane.AuditEvent;
import com.dhawal.secure_file_storage.controlplane.Permit;
import com.dhawal.secure_file_storage.dto.FileMeta;
import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.storage.ApiException;
import com.dhawal.secure_file_storage.storage.ContentService;
import com.dhawal.secure_file_storage.storage.ControlPlane;
import com.dhawal.secure_file_storage.storage.RequestInfo;
import com.dhawal.secure_file_storage.storage.UploadService;
import com.dhawal.secure_file_storage.security.AuthenticatedUserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/files")
public class FilesController {

    private static final Pattern RANGE = Pattern.compile("^bytes=(\\d*)-(\\d*)$");

    public record InitiateRequest(String name, String mime, Long size) {
    }

    public record CompleteRequest(String sha256) {
    }

    private final UploadService uploads;
    private final ContentService content;
    private final ControlPlane controlPlane;
    private final AuthenticatedUserService authenticatedUsers;

    public FilesController(UploadService uploads, ContentService content, ControlPlane controlPlane,
            AuthenticatedUserService authenticatedUsers) {
        this.uploads = uploads;
        this.content = content;
        this.controlPlane = controlPlane;
        this.authenticatedUsers = authenticatedUsers;
    }

    @GetMapping
    public List<FileMeta> list(HttpServletRequest request, Authentication authentication) {
        SessionUser subject = user(authentication);
        return controlPlane.catalog().list().stream()
                .map(file -> visible(file, subject, RequestInfo.of(request)))
                .filter(Objects::nonNull)
                .toList();
    }

    @GetMapping("/{fileId:f_[0-9a-f]+}")
    public FileMeta metadata(
            @PathVariable String fileId,
            HttpServletRequest request,
            Authentication authentication) {
        SessionUser subject = user(authentication);
        StoredFile file = controlPlane.catalog().find(fileId)
                .orElseThrow(() -> ApiException.notFound("That file doesn't exist."));
        return visible(file, subject, RequestInfo.of(request));
    }

    @GetMapping("/{fileId:f_[0-9a-f]+}/permit")
    public Permit permit(
            @PathVariable String fileId,
            HttpServletRequest request,
            Authentication authentication) {
        SessionUser subject = user(authentication);
        StoredFile file = controlPlane.catalog().find(fileId)
                .orElseThrow(() -> ApiException.notFound("That file doesn't exist."));
        Permit permit =
                controlPlane.permits().evaluate(fileId, subject.id());
        if (permit.actions() == null || permit.actions().isEmpty()) {
            throw ApiException.notFound("That file doesn't exist.");
        }
        controlPlane.audit().record(new AuditEvent(
                com.dhawal.secure_file_storage.storage.Ids.newEventId(), Instant.now(),
                subject.id(), subject.email(), file.id(), file.name(), "view", "permit",
                null, null, RequestInfo.of(request).ip(), RequestInfo.of(request).userAgent()));
        return permit;
    }

    @PostMapping("/initiate")
    public UploadService.Status initiate(
            @RequestBody InitiateRequest request,
            Authentication authentication) throws IOException {

        return uploads.initiate(user(authentication), request.name(), request.mime(), request.size());
    }

    @GetMapping("/{uploadId:up_[a-z2-7]+}")
    public UploadService.Status uploadStatus(
            @PathVariable String uploadId,
            Authentication authentication) throws IOException {

        return uploads.status(user(authentication), uploadId);
    }

    @PutMapping("/{uploadId:up_[a-z2-7]+}/chunk/{index:\\d+}")
    public Map<String, List<Integer>> putChunk(
            @PathVariable String uploadId,
            @PathVariable int index,
            HttpServletRequest request,
            Authentication authentication) throws IOException {

        return Map.of("received",
                uploads.putChunk(user(authentication), uploadId, index, request.getInputStream()));
    }

    @PostMapping("/{uploadId:up_[a-z2-7]+}/complete")
    public FileMeta complete(
            @PathVariable String uploadId,
            @RequestBody CompleteRequest body,
            HttpServletRequest request,
            Authentication authentication) throws IOException {

        SessionUser owner = user(authentication);
        StoredFile file = uploads.complete(owner, uploadId, body.sha256(), RequestInfo.of(request));
        return FileMeta.of(file, controlPlane.permits().evaluate(file.id(), owner.id()));
    }

    @DeleteMapping("/{uploadId:up_[a-z2-7]+}")
    public ResponseEntity<Void> abort(
            @PathVariable String uploadId,
            Authentication authentication) throws IOException {

        uploads.abort(user(authentication), uploadId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{fileId:f_[0-9a-f]+}/content")
    public void content(
            @PathVariable String fileId,
            @RequestParam(required = false) String tier,
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        SessionUser subject = user(authentication);
        RequestInfo info = RequestInfo.of(request);
        ContentService.Delivery delivery = content.content(subject, fileId, tier, info);

        long size = delivery.rendition().size();
        long start = 0;
        long end = size - 1;
        boolean partial = false;

        String range = request.getHeader(HttpHeaders.RANGE);
        if (range != null) {
            Matcher m = RANGE.matcher(range.strip());
            if (m.matches() && !(m.group(1).isEmpty() && m.group(2).isEmpty())) {
                if (m.group(1).isEmpty()) {
                    start = Math.max(0, size - Long.parseLong(m.group(2)));
                } else {
                    start = Long.parseLong(m.group(1));
                    if (!m.group(2).isEmpty()) {
                        end = Math.min(end, Long.parseLong(m.group(2)));
                    }
                }
                if (start > end || start >= size) {
                    response.setStatus(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE.value());
                    response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + size);
                    return;
                }
                partial = true;
            }
        }

        if (start == 0) {
            content.recordServed(subject, delivery, info);
        }

        headers(response, delivery);
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader("X-Quality-Tier", delivery.rendition().tier().wire());
        if (partial) {
            response.setStatus(HttpStatus.PARTIAL_CONTENT.value());
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
        }
        response.setContentLengthLong(end - start + 1);
        content.write(delivery, start, end - start + 1, response.getOutputStream());
    }

    @GetMapping("/{fileId:f_[0-9a-f]+}/thumb")
    public void thumb(
            @PathVariable String fileId,
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        ContentService.Delivery delivery = content.thumb(user(authentication), fileId, RequestInfo.of(request));
        headers(response, delivery);
        response.setContentLengthLong(delivery.rendition().size());
        content.write(delivery, 0, delivery.rendition().size(), response.getOutputStream());
    }

    private static void headers(HttpServletResponse response, ContentService.Delivery delivery) {
        response.setContentType(delivery.contentType());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.builder(delivery.inline() ? "inline" : "attachment")
                        .filename(delivery.file().name(), StandardCharsets.UTF_8).build().toString());
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        if (!delivery.rendition().mime().equals("application/pdf")) {
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
        }
    }

    private FileMeta visible(StoredFile file, SessionUser subject, RequestInfo request) {
        Permit permit =
                controlPlane.permits().evaluate(file.id(), subject.id());
        if (permit == null || permit.actions() == null || permit.actions().isEmpty()) {
            return null;
        }
        controlPlane.audit().record(new AuditEvent(
                com.dhawal.secure_file_storage.storage.Ids.newEventId(), Instant.now(),
                subject.id(), subject.email(), file.id(), file.name(), "view", "permit",
                null, null, request.ip(), request.userAgent()));
        return FileMeta.of(file, permit);
    }

    private SessionUser user(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "Your session has ended. Sign in again.");
        }
        return authenticatedUsers.resolve(authentication.getName());
    }
}
