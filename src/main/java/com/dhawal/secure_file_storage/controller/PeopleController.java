package com.dhawal.secure_file_storage.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dhawal.secure_file_storage.controlplane.PeopleService;
import com.dhawal.secure_file_storage.dto.SessionUser;
import com.dhawal.secure_file_storage.security.AuthenticatedUserService;
import com.dhawal.secure_file_storage.storage.ApiException;
import com.dhawal.secure_file_storage.storage.RequestInfo;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/files/{fileId:f_[0-9a-f]+}/people")
public class PeopleController {

    private final PeopleService people;
    private final AuthenticatedUserService authenticatedUsers;

    public PeopleController(PeopleService people, AuthenticatedUserService authenticatedUsers) {
        this.people = people;
        this.authenticatedUsers = authenticatedUsers;
    }

    @GetMapping
    public List<PeopleService.Person> list(@PathVariable String fileId, Authentication authentication) {
        return people.list(user(authentication), fileId);
    }

    @PostMapping
    public PeopleService.Person grant(
            @PathVariable String fileId,
            @RequestBody PeopleService.GrantRequest body,
            HttpServletRequest request,
            Authentication authentication) {
        return people.grant(user(authentication), fileId, body, RequestInfo.of(request));
    }

    @DeleteMapping("/{permissionId:p_[0-9a-f]+}")
    public ResponseEntity<Void> remove(
            @PathVariable String fileId,
            @PathVariable String permissionId,
            HttpServletRequest request,
            Authentication authentication) {
        people.remove(user(authentication), fileId, permissionId, RequestInfo.of(request));
        return ResponseEntity.noContent().build();
    }

    private SessionUser user(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "Your session has ended. Sign in again.");
        }
        return authenticatedUsers.resolve(authentication.getName());
    }
}
