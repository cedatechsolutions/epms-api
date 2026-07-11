package com.cems.api.dto;

/** Internal carrier for a freshly issued access token + raw refresh token. */
public record TokenPair(String accessToken, String refreshToken) {
}
