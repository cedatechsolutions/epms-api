package com.cems.api.dto;

import com.cems.api.entity.User;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class UserResponse {
    private String id;
    private String email;
    private String firstName;
    private String lastName;
    private String middleName;
    private String contactNumber;
    private boolean active;
    private Instant lastLoginAt;
    private Instant createdAt;
    private Instant updatedAt;
    private Set<String> roles;

    public static UserResponse fromEntity(User user) {
        UserResponse response = new UserResponse();
        response.id = user.getId();
        response.email = user.getEmail();
        response.firstName = user.getFirstName();
        response.lastName = user.getLastName();
        response.middleName = user.getMiddleName();
        response.contactNumber = user.getContactNumber();
        response.active = user.isActive();
        response.lastLoginAt = user.getLastLoginAt();
        response.createdAt = user.getCreatedAt();
        response.updatedAt = user.getUpdatedAt();
        response.roles = user.getRoles().stream()
                .map(role -> role.getName())
                .sorted()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return response;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public void setRoles(Set<String> roles) {
        this.roles = roles;
    }
}
