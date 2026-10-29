package com.abc.sourceservice.select;

/**
 * 失败源黑名单（学 Animeko 的「失败源冷却」）：
 * 某源在选源阶段异常/超时后，10 分钟内不再尝试它，避免每次都为“死源”白等超时。
 */
public interface SourceBlacklist {

    /** 是否在冷却期内。 */
    boolean isBlacklisted(long ruleId);

    /** 记一次失败，进入冷却期（默认 10min）。 */
    void markFailure(long ruleId, String reason);

    /** 成功一次即解除冷却（自愈，避免误判永久封禁）。 */
    void markSuccess(long ruleId);
}
