package com.cems.api.dto;

import com.cems.api.entity.User;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class AuthResponse {
    private String accessToken;
    private String tokenType;
    private String id;
    private String email;
    private String firstName;
    private String lastName;
    private String middleName;
    private String contactNumber;
    private boolean active;
    private Instant lastLoginAt;
    private Set<String> roles;

    public AuthResponse() {
    }

    public AuthResponse(String accessToken,
            String tokenType,
            String id,
            String email,
            String firstName,
            String lastName,
            String middleName,
            String contactNumber,
            boolean active,
            Instant lastLoginAt,
            Set<String> roles) {
        this.accessToken = accessToken;
        this.tokenType = tokenType;
        this.id = id;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.middleName = middleName;
        this.contactNumber = contactNumber;
        this.active = active;
        this.lastLoginAt = lastLoginAt;
        this.roles = roles;
    }

    public static AuthResponse fromUser(User user, String accessToken, String tokenType) {
        return new AuthResponse(
                accessToken,
                tokenType,
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getMiddleName(),
                user.getContactNumber(),
                user.isActive(),
                user.getLastLoginAt(),
                user.getRoles().stream()
                        .map(role -> role.getName())
                        .sorted()
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        this.middleName = middleName;
    }

    public String getContactNumber() {
        return contactNumber;
    }

    public void setContactNumber(String contactNumber) {
        this.contactNumber = contactNumber;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles;
    }
}
