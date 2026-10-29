package com.abc.sourceservice.select;

/**
 * 选源阶段（学 Animeko MediaAutoSelector 的「分阶段自动择优」）。
 *
 * <p>顺序固定：缓存 → 指定源 → 精确匹配 → 模糊匹配。
 * 每个阶段有独立超时，超时/无果即降级到下一阶段，保证首帧时间可控：
 * 热番命中缓存秒开，冷番最多等 25s 也能给出结论，而不是“一直转圈”。
 *
 * <p>超时值直接写进枚举，答辩时可以指着这张表讲“为什么是 0/5/5/15 秒”。
 */
public enum SelectStage {

    /** P1 缓存：之前解析成功过的直链（Redis，1h TTL），0 超时——命中就是 0 网络开销。 */
    CACHE("缓存命中", 3_000L),

    /** P2 指定源：用户手动切线路时只试这一条，快速失败好回退。 */
    DESIGNATED("指定源", 5_000L),

    /** P3 精确匹配：用标准标题（cleanTitle）跨源并发搜索。 */
    EXACT("精确匹配", 5_000L),

    /** P4 模糊匹配：标题归一化/去季数后再搜一次，容忍“第二季”“【】”这类噪声。 */
    FUZZY("模糊匹配", 15_000L);

    private final String label;
    private final long timeoutMs;

    SelectStage(String label, long timeoutMs) {
        this.label = label;
        this.timeoutMs = timeoutMs;
    }

    public String getLabel() {
        return label;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }
}
