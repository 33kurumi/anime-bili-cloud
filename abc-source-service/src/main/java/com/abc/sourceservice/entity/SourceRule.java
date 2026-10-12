package com.abc.sourceservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 采集规则（对齐 Kazumi 的 Plugin 模型）。
 *
 * <p>两阶段抓取：搜索（search*）→ 分集（chapter*）。
 * 占位符与 Kazumi 一致：searchURL 里用 {@code @keyword}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("source_rule")
public class SourceRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;            // 规则名/源名
    private String version;         // 版本
    private String baseUrl;         // baseURL：拼接相对链接
    private String searchUrl;       // 搜索模板，含 @keyword
    private Integer usePost;        // 搜索是否用 POST（0/1）
    private String searchMode;      // xpath / api / maccms
    private String chapterMode;     // xpath / api

    // 搜索阶段（XPath）
    private String searchList;
    private String searchName;
    private String searchResult;

    // 分集阶段（XPath）
    private String chapterRoads;
    private String chapterResult;

    // API 模式配置（JSON 字符串）
    private String searchApiCfg;
    private String chapterApiCfg;

    // css 模式：Animeko web-selector 的 searchConfig（JSON 字符串）
    private String cssConfig;

    // maccms 模式：采集接口根地址，如 https://cj.lziapi.com
    // 引擎自动调用 {macCmsApi}/api.php/provide/vod/?ac=detail&wd={keyword}
    private String macCmsApi;

    // 浏览器解析：播放页直链需无头浏览器才拿得到时置 1（复刻 Kazumi/Animeko）
    private Integer browserResolve;
    // 浏览器拦截时用于识别视频地址的正则（学 Animeko matchVideoUrl）
    private String matchVideoUrl;

    // 取直链可执行脚本（可选，预留）
    private String resolveJs;

    private String userAgent;
    private String referer;
    private String antiCrawler;     // JSON

    private Integer weight;         // 聚合排序权重（越大越优先）
    private Integer enabled;        // 1/0

    private String healthStatus;    // UP / DEGRADED / DOWN / UNKNOWN
    private Double successRate;

    @TableField(fill = FieldFill.INSERT, insertStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
