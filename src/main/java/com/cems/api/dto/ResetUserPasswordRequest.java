package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;

public class ResetUserPasswordRequest {

    @NotBlank
    private String password;

    @NotBlank
    private String passwordConfirmation;

    public ResetUserPasswordRequest() {
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPasswordConfirmation() {
        return passwordConfirmation;
    }

    public void setPasswordConfirmation(String passwordConfirmation) {
        this.passwordConfirmation = passwordConfirmation;
    }
}
