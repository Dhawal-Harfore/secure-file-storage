package com.dhawal.secure_file_storage.controller;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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

import com.dhawal.secure_file_storage.MagicLinkMailer;
import com.dhawal.secure_file_storage.MagicLinkService;
import com.dhawal.secure_file_storage.RateLimiter;
import com.dhawal.secure_file_storage.dto.SessionUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final MagicLinkService magicLinkService;
    private final MagicLinkMailer mailer;
    private final RateLimiter perAddress = new RateLimiter(3, Duration.ofMinutes(15));
    private final RateLimiter perClient = new RateLimiter(10, Duration.ofMinutes(15));

    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public AuthController(MagicLinkService magicLinkService, MagicLinkMailer mailer) {
        this.magicLinkService = magicLinkService;
        this.mailer = mailer;
    }

    @PostMapping("/magic-link")
    public ResponseEntity<?> requestMagicLink(
            @RequestBody(required = false) Map<String, String> request,
            HttpServletRequest httpRequest) {

        String email = request == null ? null : request.get("email");
        email = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);

        if (email.length() > 254 || !EMAIL.matcher(email).matches()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_email",
                    "Enter the email address your account uses.");
        }

        if (!perClient.allow(httpRequest.getRemoteAddr()) || !perAddress.allow(email)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Too many sign-in links requested. Wait a few minutes and try again.");
        }

        String token = magicLinkService.createToken(email);
        try {
            mailer.send(email, token);
        } catch (MagicLinkMailer.MailDeliveryException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "email_unavailable",
                    "We could not send the sign-in link. Try again later.");
        }

        return ResponseEntity.accepted()
                .body(Map.of("ok", true));
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verifyMagicLink(
            @RequestBody(required = false) Map<String, String> request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String token = request == null ? null : request.get("token");

        if (token == null || token.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_token",
                    "The sign-in link is missing its token. Request a fresh one.");
        }

        String email;

        try {
            email = magicLinkService.verifyToken(token);

        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_token",
                    "That sign-in link isn't valid. Request a fresh one.");

        } catch (IllegalStateException e) {
            return error(HttpStatus.GONE, "gone",
                    "That sign-in link has expired or was already used. Request a fresh one.");
        }

        HttpSession previous = httpRequest.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }

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

        return ResponseEntity.ok(Map.of("user", SessionUser.of(email)));
    }

    @GetMapping("/session")
    public ResponseEntity<?> getSession(
            Authentication authentication) {

        if (authentication == null
                || !authentication.isAuthenticated()) {
            return error(HttpStatus.UNAUTHORIZED, "unauthenticated",
                    "Your session has ended. Sign in again.");
        }

        return ResponseEntity.ok(Map.of(
                "user", SessionUser.of(authentication.getName())
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

    private static ResponseEntity<Map<String, Object>> error(
            HttpStatus status, String code, String message) {

        return ResponseEntity.status(status)
                .body(Map.of("error", Map.of(
                        "code", code,
                        "message", message,
                        "reason", ""
                )));
    }
}
