package com.abc.common.annotation;

import com.abc.common.enums.UserRole;

import java.lang.annotation.*;

/**
 * Require a specific role to access the annotated controller/method.
 * Checked by the gateway filter and/or service-level interceptors.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRole {
    UserRole value() default UserRole.USER;
}
