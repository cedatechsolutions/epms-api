package com.cems.api.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateUserStatusRequest {

    @NotBlank
    private String status;

    public UpdateUserStatusRequest() {
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
