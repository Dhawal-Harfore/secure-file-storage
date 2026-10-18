package com.dhawal.secure_file_storage.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dhawal.secure_file_storage.MagicLinkService;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final MagicLinkService magicLinkService;

    public AuthController(MagicLinkService magicLinkService) {
        this.magicLinkService = magicLinkService;
    }

    @PostMapping("/magic-link")
    public ResponseEntity<Map<String, Boolean>> requestMagicLink(
            @RequestBody Map<String, String> request) {

        String email = request.get("email");

        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false));
        }

        String token = magicLinkService.createToken(email);

        // TEMPORARY: local testing only.
        // Remove before production deployment.
        System.out.println("Magic-link token: " + token);

        return ResponseEntity.accepted()
                .body(Map.of("ok", true));
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifyMagicLink(
            @RequestBody Map<String, String> request) {

        String token = request.get("token");

        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", Map.of(
                            "code", "invalid_token",
                            "message", "Token is required.",
                            "reason", "Provide a valid magic-link token."
                    )));
        }

        try {
            String email = magicLinkService.verifyToken(token);

            return ResponseEntity.ok(Map.of(
                    "email", email,
                    "message", "Magic-link verified successfully"
            ));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", Map.of(
                            "code", "invalid_token",
                            "message", "Invalid magic-link token.",
                            "reason", "Request a new magic link."
                    )));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", Map.of(
                            "code", "token_gone",
                            "message", "Token expired or already used.",
                            "reason", "Request a new magic link."
                    )));
        }
    }
}
