package com.dhawal.secure_file_storage.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dhawal.secure_file_storage.controlplane.AuditEvent;
import com.dhawal.secure_file_storage.controlplane.FileKind;
import com.dhawal.secure_file_storage.controlplane.Permit;
import com.dhawal.secure_file_storage.controlplane.QualityTier;
import com.dhawal.secure_file_storage.controlplane.StoredFile;
import com.dhawal.secure_file_storage.dto.FileMeta;
import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.persistence.AccessLevel;
import com.dhawal.secure_file_storage.persistence.Action;
import com.dhawal.secure_file_storage.persistence.Share;
import com.dhawal.secure_file_storage.persistence.ShareRepository;
import com.dhawal.secure_file_storage.persistence.StoredFileRepository;
import com.dhawal.secure_file_storage.persistence.UserAccountRepository;

@Service
public class ShareService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration LONGEST_LINK = Duration.ofDays(366);

    public record CreateRequest(String accessLevel, List<String> actions, Instant expiresAt, Integer maxViews) {
    }

    public record ShareView(
            String id,
            String fileId,
            String token,
            String accessLevel,
            List<String> actions,
            Instant expiresAt,
            Integer maxViews,
            int viewsUsed,
            Instant revokedAt,
            Instant createdAt,
            String createdBy) {
    }

    public record PublicShare(FileMeta file, Permit permit) {
    }

    public record Opened(ContentService.Delivery delivery, String shareId) {
    }

    private final ShareRepository shares;
    private final StoredFileRepository storedFiles;
    private final UserAccountRepository users;
    private final ControlPlane controlPlane;
    private final ContentService content;

    public ShareService(ShareRepository shares, StoredFileRepository storedFiles, UserAccountRepository users,
            ControlPlane controlPlane, ContentService content) {
        this.shares = shares;
        this.storedFiles = storedFiles;
        this.users = users;
        this.controlPlane = controlPlane;
        this.content = content;
    }

    @Transactional(readOnly = true)
    public List<ShareView> list(SessionUser owner, String fileId) {
        require(owner, fileId, "share");
        return shares.findByFileIdOrderByCreatedAtDesc(fileId).stream().map(s -> view(s, null)).toList();
    }

    @Transactional
    public ShareView create(SessionUser owner, String fileId, CreateRequest request, RequestInfo info) {
        StoredFile file = require(owner, fileId, "share");

        AccessLevel level = level(request.accessLevel());
        Set<Action> actions = actions(level, request.actions());
        Instant now = Instant.now();

        if (request.expiresAt() != null
                && (!request.expiresAt().isAfter(now) || request.expiresAt().isAfter(now.plus(LONGEST_LINK)))) {
            throw ApiException.badRequest("invalid_share", "A link can end at most a year from now, and not in the past.");
        }
        if (request.maxViews() != null && (request.maxViews() < 1 || request.maxViews() > 100_000)) {
            throw ApiException.badRequest("invalid_share", "Maximum views has to be between 1 and 100000.");
        }

        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);

        Share share = new Share(newShareId(), storedFiles.getReferenceById(fileId), hash(token), level, actions,
                request.expiresAt(), request.maxViews(),
                users.findById(owner.id()).orElseThrow(() -> ApiException.notFound("That file doesn't exist.")));
        shares.saveAndFlush(share);

        record(owner, file, "share", "permit", null, share.getId(), info);
        return view(share, token);
    }

    @Transactional
    public ShareView revoke(SessionUser owner, String shareId, RequestInfo info) {
        Share share = shares.findById(shareId).orElseThrow(() -> ApiException.notFound("That link no longer exists."));
        StoredFile file = require(owner, share.getFile().getId(), "revoke");
        if (share.getRevokedAt() == null) {
            share.revoke(Instant.now());
            shares.saveAndFlush(share);
            record(owner, file, "revoke", "permit", null, share.getId(), info);
        }
        return view(share, null);
    }

    @Transactional(readOnly = true)
    public PublicShare open(String token) {
        Share share = live(token);
        StoredFile file = file(share);
        Permit permit = permit(share, file.kind(), token);
        FileMeta meta = FileMeta.of(file, permit);
        return new PublicShare(new FileMeta(meta.id(), meta.name(), meta.mime(), meta.size(), meta.kind(),
                null, null, meta.createdAt(), meta.updatedAt(), meta.renditions(), permit), permit);
    }

    @Transactional(readOnly = true)
    public Opened content(String token, String tier) {
        Share share = live(token);
        StoredFile file = file(share);
        return new Opened(content.forPermit(file, permit(share, file.kind(), token), tier), share.getId());
    }

    @Transactional
    public void countView(Opened opened, RequestInfo info) {
        if (shares.countView(opened.shareId(), Instant.now()) == 0) {
            Share share = shares.findById(opened.shareId()).orElseThrow(() -> gone("This link doesn't exist."));
            throw gone(endedMessage(share, Instant.now()));
        }
        StoredFile file = opened.delivery().file();
        controlPlane.audit().record(new AuditEvent(Ids.newEventId(), Instant.now(), null, null, file.id(),
                file.name(), "view", "permit", opened.delivery().rendition().tier(), opened.shareId(),
                info.ip(), info.userAgent()));
    }

    static Permit permit(Share share, FileKind kind, String token) {
        List<String> actions = share.getActions().stream()
                .map(a -> a.name().toLowerCase(Locale.ROOT)).sorted().toList();
        boolean media = kind != FileKind.OTHER;
        QualityTier tier = switch (share.getAccessLevel()) {
            case VIEWER -> media ? QualityTier.REDUCED
                    : actions.contains("download") ? QualityTier.ORIGINAL : QualityTier.METADATA;
            case GUEST -> media ? QualityTier.LOWEST : QualityTier.METADATA;
            default -> QualityTier.METADATA;
        };
        String fileId = share.getFile().getId();
        String level = share.getAccessLevel().name().toLowerCase(Locale.ROOT);
        return new Permit(fileId, "share:" + token, level, actions, tier,
                tier == QualityTier.METADATA ? null : Ids.keyHandle(fileId, tier),
                share.getExpiresAt(), share.getMaxViews(), share.getViewsUsed(), "share:" + level);
    }

    private StoredFile require(SessionUser owner, String fileId, String action) {
        StoredFile file = controlPlane.catalog().find(fileId)
                .orElseThrow(() -> ApiException.notFound("That file doesn't exist."));
        Permit permit = controlPlane.permits().evaluate(fileId, owner.id());
        if (permit == null || permit.actions() == null || permit.actions().isEmpty()) {
            throw ApiException.notFound("That file doesn't exist.");
        }
        if (!permit.allows(action)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "denied",
                    "You don't have permission to " + action + " links for this file.", permit.reason());
        }
        return file;
    }

    private Share live(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{16,128}")) {
            throw ApiException.notFound("This link doesn't exist.");
        }
        Share share = shares.findByTokenHash(hash(token))
                .orElseThrow(() -> ApiException.notFound("This link doesn't exist."));
        Instant now = Instant.now();
        boolean expired = share.getExpiresAt() != null && !share.getExpiresAt().isAfter(now);
        if (share.getRevokedAt() != null || expired) {
            throw gone(endedMessage(share, now));
        }
        return share;
    }

    private StoredFile file(Share share) {
        return controlPlane.catalog().find(share.getFile().getId())
                .orElseThrow(() -> ApiException.notFound("This link doesn't exist."));
    }

    private void record(SessionUser actor, StoredFile file, String action, String result, QualityTier tier,
            String shareId, RequestInfo info) {
        controlPlane.audit().record(new AuditEvent(Ids.newEventId(), Instant.now(), actor.id(), actor.email(),
                file.id(), file.name(), action, result, tier, shareId, info.ip(), info.userAgent()));
    }

    private static String endedMessage(Share share, Instant now) {
        if (share.getRevokedAt() != null) {
            return "This link was revoked by its owner.";
        }
        if (share.getExpiresAt() != null && !share.getExpiresAt().isAfter(now)) {
            return "This link has expired.";
        }
        return "This link has reached its view limit.";
    }

    private static ApiException gone(String message) {
        return new ApiException(HttpStatus.GONE, "gone", message);
    }

    private static AccessLevel level(String value) {
        String level = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        return switch (level) {
            case "viewer" -> AccessLevel.VIEWER;
            case "guest" -> AccessLevel.GUEST;
            default -> throw ApiException.badRequest("invalid_share", "A link can be for a viewer or a guest.");
        };
    }

    private static Set<Action> actions(AccessLevel level, List<String> requested) {
        Set<Action> actions = EnumSet.of(Action.VIEW);
        for (String name : requested == null ? List.<String>of() : requested) {
            switch (name == null ? "" : name.strip().toLowerCase(Locale.ROOT)) {
                case "view" -> {
                }
                case "download" -> {
                    if (level == AccessLevel.GUEST) {
                        throw ApiException.badRequest("invalid_share", "Guest links can't allow downloads.");
                    }
                    actions.add(Action.DOWNLOAD);
                }
                default -> throw ApiException.badRequest("invalid_share", "A link can only allow view and download.");
            }
        }
        return actions;
    }

    private static ShareView view(Share share, String token) {
        return new ShareView(share.getId(), share.getFile().getId(), token,
                share.getAccessLevel().name().toLowerCase(Locale.ROOT),
                share.getActions().stream().map(a -> a.name().toLowerCase(Locale.ROOT)).sorted().toList(),
                share.getExpiresAt(), share.getMaxViews(), share.getViewsUsed(), share.getRevokedAt(),
                share.getCreatedAt(), share.getCreatedBy().getId());
    }

    private static String newShareId() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return "s_" + HexFormat.of().formatHex(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
