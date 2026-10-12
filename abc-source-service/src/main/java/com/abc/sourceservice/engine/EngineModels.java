package com.abc.sourceservice.engine;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 规则引擎的数据结构（对齐 Kazumi 的 SearchItem / Road）。 */
public final class EngineModels {
    private EngineModels() { }

    /** 搜索结果项：名称 + 详情页 URL */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SearchItem {
        private String name;
        private String url;
    }

    /**
     * 播放线路：一条线路含若干集。
     * 用两条平行数组表示，对齐 Kazumi Road{data, identifier}：
     * urls=集链接，names=集名。
     */
    @Data
    @NoArgsConstructor
    public static class Road {
        private String name;
        private List<String> urls;
        private List<String> names;

        public Road(String name, List<String> urls, List<String> names) {
            this.name = name;
            this.urls = urls;
            this.names = names;
        }
    }
}
