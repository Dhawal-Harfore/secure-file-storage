package com.dhawal.secure_file_storage.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.security.AuthenticatedUserService;
import com.dhawal.secure_file_storage.storage.ApiException;
import com.dhawal.secure_file_storage.storage.ContentService;
import com.dhawal.secure_file_storage.storage.RequestInfo;
import com.dhawal.secure_file_storage.storage.ShareService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api")
public class SharesController {

    private final ShareService shares;
    private final ContentService content;
    private final AuthenticatedUserService authenticatedUsers;

    public SharesController(ShareService shares, ContentService content, AuthenticatedUserService authenticatedUsers) {
        this.shares = shares;
        this.content = content;
        this.authenticatedUsers = authenticatedUsers;
    }

    @GetMapping("/files/{fileId:f_[0-9a-f]+}/shares")
    public List<ShareService.ShareView> list(@PathVariable String fileId, Authentication authentication) {
        return shares.list(user(authentication), fileId);
    }

    @PostMapping("/files/{fileId:f_[0-9a-f]+}/shares")
    public ShareService.ShareView create(
            @PathVariable String fileId,
            @RequestBody ShareService.CreateRequest body,
            HttpServletRequest request,
            Authentication authentication) {
        return shares.create(user(authentication), fileId, body, RequestInfo.of(request));
    }

    @PostMapping("/shares/{shareId:s_[0-9a-f]+}/revoke")
    public ShareService.ShareView revoke(
            @PathVariable String shareId,
            HttpServletRequest request,
            Authentication authentication) {
        return shares.revoke(user(authentication), shareId, RequestInfo.of(request));
    }

    @GetMapping("/s/{token}")
    public ShareService.PublicShare open(@PathVariable String token) {
        return shares.open(token);
    }

    @GetMapping("/s/{token}/content")
    public void content(
            @PathVariable String token,
            @RequestParam(required = false) String tier,
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        ShareService.Opened opened = shares.content(token, tier);
        RequestInfo info = RequestInfo.of(request);
        ContentResponses.stream(request, response, content, opened.delivery(),
                () -> shares.countView(opened, info));
    }

    private SessionUser user(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "Your session has ended. Sign in again.");
        }
        return authenticatedUsers.resolve(authentication.getName());
    }
}
