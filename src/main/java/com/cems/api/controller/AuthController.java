package com.cems.api.controller;

import com.cems.api.dto.ApiResponse;
import com.cems.api.dto.AuthRequest;
import com.cems.api.dto.AuthResponse;
import com.cems.api.dto.ChangePasswordRequest;
import com.cems.api.dto.ForgotPasswordRequest;
import com.cems.api.dto.RefreshRequest;
import com.cems.api.dto.ResetPasswordRequest;
import com.cems.api.security.JwtTokenBlocklistService;
import com.cems.api.security.JwtUtils;
import com.cems.api.security.RecaptchaService;
import com.cems.api.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final JwtUtils jwtUtils;
    private final JwtTokenBlocklistService jwtTokenBlocklistService;
    private final RecaptchaService recaptchaService;

    public AuthController(AuthService authService,
            JwtUtils jwtUtils,
            JwtTokenBlocklistService jwtTokenBlocklistService,
            RecaptchaService recaptchaService) {
        this.authService = authService;
        this.jwtUtils = jwtUtils;
        this.jwtTokenBlocklistService = jwtTokenBlocklistService;
        this.recaptchaService = recaptchaService;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody AuthRequest authRequest,
            HttpServletRequest request) {
        verifyRecaptcha(authRequest, request);
        return ResponseEntity.ok(authService.login(authRequest.getEmail(), authRequest.getPassword()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse> logout(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        String jwt = parseJwt(authorizationHeader);
        if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
            // Revoke the user's refresh tokens, then blocklist this access token until it expires.
            authService.logout(jwtUtils.getUsernameFromJwtToken(jwt));
            jwtTokenBlocklistService.revoke(jwt, jwtUtils.getExpirationFromJwtToken(jwt));
        }
        return ResponseEntity.ok(new ApiResponse("Logged out successfully."));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request.getEmail());
        return ResponseEntity.ok(new ApiResponse(
                "If an account exists for that email, a password reset link has been sent."));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.getToken(), request.getPassword(), request.getPasswordConfirmation());
        return ResponseEntity.ok(new ApiResponse("Password has been reset successfully."));
    }

    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required.");
        }
        authService.changePassword(
                authentication.getName(),
                request.getCurrentPassword(),
                request.getNewPassword(),
                request.getNewPasswordConfirmation());
        return ResponseEntity.ok(new ApiResponse("Password changed successfully."));
    }

    private String parseJwt(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7);
        }
        return null;
    }

    private void verifyRecaptcha(AuthRequest authRequest, HttpServletRequest request) {
        if (!recaptchaService.isEnabled()) {
            return;
        }

        if (authRequest.getCaptchaToken() == null || authRequest.getCaptchaToken().isBlank()) {
            throw new IllegalArgumentException("Complete the reCAPTCHA challenge.");
        }

        try {
            boolean verified = recaptchaService.verifyToken(authRequest.getCaptchaToken(), request.getRemoteAddr());
            if (!verified) {
                throw new IllegalArgumentException("reCAPTCHA verification failed. Please try again.");
            }
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
    }
}
