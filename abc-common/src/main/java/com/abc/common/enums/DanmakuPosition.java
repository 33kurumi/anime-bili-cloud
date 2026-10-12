package com.abc.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Danmaku display position constants.
 * Reference: Bilibili / animeko danmaku protocol.
 */
@Getter
@AllArgsConstructor
public enum DanmakuPosition {
    SCROLL(0, "滚动弹幕"),
    TOP(1, "顶端弹幕"),
    BOTTOM(2, "底部弹幕");

    private final int code;
    private final String label;

    public static DanmakuPosition fromCode(int code) {
        for (DanmakuPosition p : values()) {
            if (p.code == code) return p;
        }
        return SCROLL;
    }
}
