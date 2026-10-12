package com.abc.sourceservice.engine;

import lombok.Getter;

/**
 * 规则引擎失败语义（对齐 Kazumi rule_engine_models.dart 的异常族）。
 * 用于源健康度分类与故障转移决策。
 */
@Getter
public class RuleException extends RuntimeException {

    public enum Reason {
        CAPTCHA,        // 需验证码
        NO_RESULT,      // 无结果
        SEARCH_ERROR,   // 搜索阶段失败
        CHAPTER_ERROR,  // 分集阶段失败
        FORMAT_ERROR    // 规则格式错误
    }

    private final Reason reason;
    private final String ruleName;

    public RuleException(Reason reason, String ruleName, String message) {
        super(message);
        this.reason = reason;
        this.ruleName = ruleName;
    }

    public RuleException(Reason reason, String ruleName, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.ruleName = ruleName;
    }

    public static RuleException noResult(String ruleName) {
        return new RuleException(Reason.NO_RESULT, ruleName, "无搜索结果");
    }

    public static RuleException searchError(String ruleName, Throwable e) {
        return new RuleException(Reason.SEARCH_ERROR, ruleName, "搜索失败: " + e.getMessage(), e);
    }

    public static RuleException chapterError(String ruleName, Throwable e) {
        return new RuleException(Reason.CHAPTER_ERROR, ruleName, "分集解析失败: " + e.getMessage(), e);
    }

    public static RuleException formatError(String ruleName, String msg) {
        return new RuleException(Reason.FORMAT_ERROR, ruleName, "规则格式错误: " + msg);
    }
}
