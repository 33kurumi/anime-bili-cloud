package com.abc.danmakuservice.source.dandanplay;

/**
 * 上游（dandanplay）弹幕的三级匹配方式，按可信度从高到低排列。
 *
 * <p>设计意图：把「匹配到哪一层」显式落库，而不是匹配成功就完事。
 * 答辩时可以统计「BANGUMI_ID 命中率 / 需要降级到模糊匹配的比例」，
 * 也方便排查"弹幕串集"时到底哪一级出的错。
 */
public enum DanmakuMatchMethod {

    /** 精确：用 Bangumi subject id 取到官方剧集表，再按集号命中（最可靠） */
    BANGUMI_ID("Bangumi ID 精确匹配", 3),

    /** 标题归一化完全相等 + 集号；失败时对集号做 ±1 容错（应对"第0话/特别篇"偏移） */
    TITLE_EXACT("标题精确匹配", 2),

    /** 标题相似度（Jaccard/编辑距离）≥ 阈值 + 集号 ±1 容错 */
    TITLE_FUZZY("标题模糊匹配", 1),

    /** 未匹配 */
    NONE("未匹配", 0);

    private final String label;
    private final int level;

    DanmakuMatchMethod(String label, int level) {
        this.label = label;
        this.level = level;
    }

    public String getLabel() {
        return label;
    }

    public int getLevel() {
        return level;
    }
}
