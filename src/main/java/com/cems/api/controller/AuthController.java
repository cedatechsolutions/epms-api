package com.cems.api.controller;

import com.cems.api.dto.ApiResponse;
import com.cems.api.dto.AuthRequest;
import com.cems.api.dto.AuthResponse;
import com.cems.api.entity.User;
import com.cems.api.repository.UserRepository;
import com.cems.api.security.JwtTokenBlocklistService;
import com.cems.api.security.JwtUtils;
import com.cems.api.security.RecaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final JwtTokenBlocklistService jwtTokenBlocklistService;
    private final RecaptchaService recaptchaService;
    private final UserRepository userRepository;

    public AuthController(AuthenticationManager authenticationManager,
            JwtUtils jwtUtils,
            JwtTokenBlocklistService jwtTokenBlocklistService,
            RecaptchaService recaptchaService,
            UserRepository userRepository) {
        this.authenticationManager = authenticationManager;
        this.jwtUtils = jwtUtils;
        this.jwtTokenBlocklistService = jwtTokenBlocklistService;
        this.recaptchaService = recaptchaService;
        this.userRepository = userRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> authenticateUser(@Valid @RequestBody AuthRequest authRequest,
            HttpServletRequest request) {
        verifyRecaptcha(authRequest, request);

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(authRequest.getEmail(), authRequest.getPassword()));

        User user = userRepository.findByEmail(authRequest.getEmail())
                .orElseThrow(() -> new RuntimeException("User not found after authentication"));
        user.setLastLoginAt(Instant.now());
        user = userRepository.save(user);

        String jwt = jwtUtils.generateToken(authentication);

        return ResponseEntity.ok(AuthResponse.fromUser(user, jwt, "Bearer"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse> logout(@RequestHeader(value = "Authorization", required = false) String authorizationHeader) {
        String jwt = parseJwt(authorizationHeader);

        if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
            jwtTokenBlocklistService.revoke(jwt, jwtUtils.getExpirationFromJwtToken(jwt));
        }

        return ResponseEntity.ok(new ApiResponse("Logged out successfully."));
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
