package com.abc.sourceservice.security;

/**
 * SSRF 拦截异常：URL 指向内网/回环/非法协议时抛出。
 * 由 {@link SsrfGuard} 统一抛出，Controller 捕获后返回 403。
 */
public class SsrfBlockedException extends RuntimeException {

    public SsrfBlockedException(String message) {
        super(message);
    }
}
