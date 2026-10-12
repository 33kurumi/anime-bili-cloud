package com.abc.common.result;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified API response wrapper.
 * All controller methods across all microservices return this type.
 *
 * @param <T> data payload type
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> {

    /** Status code: 200=success, others=error */
    private int code;

    /** Human-readable message */
    private String message;

    /** Response payload, nullable */
    private T data;

    /** Timestamp (milliseconds since epoch) */
    private long timestamp;

    // ─── Factory methods ────────────────────────────────────────

    public static <T> R<T> ok() {
        return new R<>(200, "success", null, System.currentTimeMillis());
    }

    public static <T> R<T> ok(T data) {
        return new R<>(200, "success", data, System.currentTimeMillis());
    }

    public static <T> R<T> ok(String message, T data) {
        return new R<>(200, message, data, System.currentTimeMillis());
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null, System.currentTimeMillis());
    }

    public static <T> R<T> fail(String message) {
        return new R<>(500, message, null, System.currentTimeMillis());
    }

    public static <T> R<T> unauthorized(String message) {
        return new R<>(401, message, null, System.currentTimeMillis());
    }

    public static <T> R<T> forbidden(String message) {
        return new R<>(403, message, null, System.currentTimeMillis());
    }

    public static <T> R<T> notFound(String message) {
        return new R<>(404, message, null, System.currentTimeMillis());
    }

    public static <T> R<T> badRequest(String message) {
        return new R<>(400, message, null, System.currentTimeMillis());
    }
}
