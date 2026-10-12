package com.abc.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * User roles for RBAC.
 */
@Getter
@AllArgsConstructor
public enum UserRole {
    USER("普通用户"),
    VIP("VIP会员"),
    ADMIN("管理员");

    private final String label;
}
