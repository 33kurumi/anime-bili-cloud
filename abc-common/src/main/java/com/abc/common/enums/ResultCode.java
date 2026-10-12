package com.abc.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Common result status codes used across all microservices.
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(200, "操作成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂不可用"),

    // ─── Auth (1000-1099) ──────────────────────────
    AUTH_LOGIN_FAILED(1001, "用户名或密码错误"),
    AUTH_TOKEN_INVALID(1002, "Token无效或已过期"),
    AUTH_ACCOUNT_DISABLED(1003, "账号已被禁用"),
    AUTH_CAPTCHA_ERROR(1004, "验证码错误"),
    AUTH_USERNAME_EXIST(1005, "用户名已存在"),
    AUTH_EMAIL_EXIST(1006, "邮箱已注册"),

    // ─── User (1100-1199) ──────────────────────────
    USER_NOT_FOUND(1101, "用户不存在"),
    USER_ALREADY_FOLLOWED(1102, "已关注该用户"),
    USER_NOT_FOLLOWED(1103, "未关注该用户"),

    // ─── Anime (1200-1299) ─────────────────────────
    ANIME_NOT_FOUND(1201, "番剧不存在"),
    EPISODE_NOT_FOUND(1202, "剧集不存在"),

    // ─── Comment (1300-1399) ───────────────────────
    COMMENT_NOT_FOUND(1301, "评论不存在"),
    RATING_OUT_OF_RANGE(1302, "评分超出范围(1-10)"),

    // ─── Danmaku (1400-1499) ───────────────────────
    DANMAKU_SEND_FAILED(1401, "弹幕发送失败"),

    // ─── File/Upload (1500-1599) ───────────────────
    FILE_UPLOAD_FAILED(1501, "文件上传失败"),
    FILE_SIZE_EXCEEDED(1502, "文件大小超出限制"),
    FILE_TYPE_NOT_SUPPORTED(1503, "不支持的文件类型"),

    // ─── Search (1600-1699) ────────────────────────
    SEARCH_FAILED(1601, "搜索失败");

    private final int code;
    private final String message;
}
