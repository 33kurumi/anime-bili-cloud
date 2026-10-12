package com.abc.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JWT token payload sent to clients after login.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenDto {
    private String accessToken;
    private String refreshToken;
    private long expiresIn;
    private Long userId;
    private String username;
    private String role;
}
