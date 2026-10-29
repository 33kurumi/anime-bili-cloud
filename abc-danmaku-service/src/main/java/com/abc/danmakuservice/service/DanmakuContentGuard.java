package com.abc.danmakuservice.service;

import com.abc.danmakuservice.config.DanmakuProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 弹幕内容守卫：屏蔽词/正则匹配 + 文本归一化入口。
 *
 * <p>设计意图：屏蔽规则不写死在代码里，而是配置在 {@code danmaku.shield.patterns}。
 * 规则按「正则」处理（普通关键词也是合法的正则），支持 {@code 广告|代购|加微信} 这类批量写法；
 * 编译失败的单条规则只告警不启动失败，避免一条错配置拖垮整个服务。
 *
 * <p>命中后的处理策略是「落库 + 标记 blocked=true + 不广播」：
 * 而不是直接丢弃——保留证据便于后台审核与答辩演示"违规弹幕可追溯"。
 */
@Slf4j
@Component
public class DanmakuContentGuard {

    /** 已编译的屏蔽规则；为空表示不屏蔽 */
    private final List<Pattern> shieldPatterns;

    public DanmakuContentGuard(DanmakuProperties properties) {
        List<String> raw = properties.getShield() == null ? List.of() : properties.getShield().getPatterns();
        this.shieldPatterns = compile(raw);
        log.info("弹幕屏蔽规则加载完成: {} 条 -> {}", raw.size(), shieldPatterns.size());
    }

    /** 把配置里的字符串正则编译为 Pattern（大小写不敏感 + Unicode），单条失败仅告警 */
    public static List<Pattern> compile(List<String> rawPatterns) {
        if (rawPatterns == null || rawPatterns.isEmpty()) {
            return Collections.emptyList();
        }
        List<Pattern> compiled = new ArrayList<>(rawPatterns.size());
        for (String raw : rawPatterns) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                compiled.add(Pattern.compile(raw, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException e) {
                log.warn("屏蔽规则语法错误已忽略: pattern='{}' 原因={}", raw, e.getDescription());
            }
        }
        return compiled;
    }

    /** 文本是否命中任一屏蔽规则 */
    public boolean isShielded(String text) {
        return matchesAny(shieldPatterns, text);
    }

    /** 纯函数版（便于单测）：只要有一条规则命中即视为屏蔽 */
    public static boolean matchesAny(List<Pattern> patterns, String text) {
        if (patterns == null || patterns.isEmpty() || text == null || text.isEmpty()) {
            return false;
        }
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return true;
            }
        }
        return false;
    }

    /** 已加载规则条数（健康检查/答辩展示用） */
    public int ruleCount() {
        return shieldPatterns.size();
    }
}
