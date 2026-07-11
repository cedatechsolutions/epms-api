package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Used by an authenticated user to change their own password (incl. forced first-login change). */
public class ChangePasswordRequest {

    @NotBlank
    private String currentPassword;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters.")
    private String newPassword;

    @NotBlank
    private String newPasswordConfirmation;

    public ChangePasswordRequest() {
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }

    public String getNewPasswordConfirmation() {
        return newPasswordConfirmation;
    }

    public void setNewPasswordConfirmation(String newPasswordConfirmation) {
        this.newPasswordConfirmation = newPasswordConfirmation;
    }
}
