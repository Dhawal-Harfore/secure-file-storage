
package com.dhawal.secure_file_storage.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dhawal.secure_file_storage.MagicLinkService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final MagicLinkService magicLinkService;

    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

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

    // TEMPORARY: local testing only
    System.out.println("Magic-link token: " + token);

    return ResponseEntity.accepted()
            .body(Map.of("ok", true));
}


    @PostMapping("/verify")
    public ResponseEntity<?> verifyMagicLink(
            @RequestBody Map<String, String> request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String token = request.get("token");

        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", Map.of(
                            "code", "invalid_token",
                            "message", "Token is required.",
                            "reason", "Provide a valid magic-link token."
                    )));
        }

        try {
            String email = magicLinkService.verifyToken(token);

            Authentication authentication =
                    UsernamePasswordAuthenticationToken.authenticated(
                            email,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_USER"))
                    );

            SecurityContext context =
                    SecurityContextHolder.createEmptyContext();

            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);

            securityContextRepository.saveContext(
                    context,
                    httpRequest,
                    httpResponse
            );

            return ResponseEntity.ok(Map.of(
                    "user", Map.of("email", email)
            ));

        } catch (IllegalArgumentException e) {
            SecurityContextHolder.clearContext();

            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", Map.of(
                            "code", "invalid_token",
                            "message", "Invalid magic-link token.",
                            "reason", "Request a new magic link."
                    )));

        } catch (IllegalStateException e) {
            SecurityContextHolder.clearContext();

            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("error", Map.of(
                            "code", "token_gone",
                            "message", "Token expired or already used.",
                            "reason", "Request a new magic link."
                    )));
        }
    }

    @GetMapping("/session")
    public ResponseEntity<?> getSession(
            Authentication authentication) {

        if (authentication == null
                || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", Map.of(
                            "code", "unauthenticated",
                            "message", "No active session.",
                            "reason", "Verify a valid magic link."
                    )));
        }

        return ResponseEntity.ok(Map.of(
                "user", Map.of("email", authentication.getName())
        ));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {

        new SecurityContextLogoutHandler()
                .logout(request, response, authentication);

        return ResponseEntity.noContent().build();
    }
}
