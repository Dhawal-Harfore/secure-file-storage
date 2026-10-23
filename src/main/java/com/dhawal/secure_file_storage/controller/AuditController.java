package com.dhawal.secure_file_storage.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.dhawal.secure_file_storage.controlplane.AuditQueryService;
import com.dhawal.secure_file_storage.security.AuthenticatedUserService;
import com.dhawal.secure_file_storage.storage.ApiException;

@RestController
public class AuditController {

    private final AuditQueryService audit;
    private final AuthenticatedUserService authenticatedUsers;

    public AuditController(AuditQueryService audit, AuthenticatedUserService authenticatedUsers) {
        this.audit = audit;
        this.authenticatedUsers = authenticatedUsers;
    }

    @GetMapping("/api/audit")
    public AuditQueryService.AuditPage search(
            @RequestParam(required = false) String fileId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "Your session has ended. Sign in again.");
        }
        return audit.search(authenticatedUsers.resolve(authentication.getName()),
                fileId, action, result, from, to, page, pageSize);
    }
}
