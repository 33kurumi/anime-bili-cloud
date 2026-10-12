package com.abc.common.annotation;

import java.lang.annotation.*;

/**
 * Mark an endpoint as public — skip JWT authentication in gateway filter.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SkipAuth {
}
