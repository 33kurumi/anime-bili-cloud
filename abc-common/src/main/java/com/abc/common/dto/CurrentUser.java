package com.abc.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Current user information propagated through gateway filter.
 * Microservices read this from request attributes.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentUser {
    private Long userId;
    private String username;
    private String role;
}
