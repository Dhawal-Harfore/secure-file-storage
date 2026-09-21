package com.dhawal.secure_file_storage.controller;

import com.dhawal.secure_file_storage.MagicLinkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

        magicLinkService.createToken(email);

        System.out.println("Magic-link requested for: " + email);

        return ResponseEntity.accepted()
                .body(Map.of("ok", true));
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, String>> verifyMagicLink(
            @RequestBody Map<String, String> request) {

        String token = request.get("token");

        String email = magicLinkService.verifyToken(token);

        return ResponseEntity.ok(
                Map.of(
                        "email", email,
                        "message", "Magic-link verified successfully"
                )
        );
    }
}