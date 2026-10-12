package com.abc.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Anime types supported by the platform.
 */
@Getter
@AllArgsConstructor
public enum AnimeType {
    TV("TV动画"),
    MOVIE("剧场版"),
    OVA("OVA"),
    ONA("ONA"),
    SPECIAL("特别篇");

    private final String label;
}
