-- ═══════════════════════════════════════════════════════════════
-- abc-source-service — 采集规则表
-- 支持三种模式：
--   maccms  苹果CMS 采集接口（免浏览器，直接给明文 m3u8 直链）—— 推荐首选
--   xpath   抓网页 + XPath（Jsoup/JsoupXpath）—— 兼容 Kazumi 规则
--   api     抓接口 + JSONPath
-- ═══════════════════════════════════════════════════════════════
USE abc_anime;

DROP TABLE IF EXISTS `source_rule`;
CREATE TABLE `source_rule` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `name`            VARCHAR(100) NOT NULL COMMENT '规则名/源名',
    `version`         VARCHAR(20)  DEFAULT '1.0',
    `base_url`        VARCHAR(500) NOT NULL COMMENT 'baseURL：拼接相对链接',
    `search_url`      VARCHAR(1000) COMMENT 'searchURL 模板，含 @keyword（xpath/api 模式用）',
    `use_post`        TINYINT      DEFAULT 0 COMMENT '搜索是否用 POST',
    `search_mode`     VARCHAR(10)  DEFAULT 'xpath' COMMENT 'xpath/api/maccms',
    `chapter_mode`    VARCHAR(10)  DEFAULT 'xpath' COMMENT 'xpath/api',
    `search_list`     VARCHAR(1000) COMMENT 'searchList XPath',
    `search_name`     VARCHAR(1000) COMMENT 'searchName XPath',
    `search_result`   VARCHAR(1000) COMMENT 'searchResult XPath',
    `chapter_roads`   VARCHAR(2000) COMMENT 'chapterRoads XPath',
    `chapter_result`  VARCHAR(2000) COMMENT 'chapterResult XPath',
    `search_api_cfg`  TEXT         COMMENT 'searchApiConfig(JSON)',
    `chapter_api_cfg` TEXT         COMMENT 'chapterApiConfig(JSON)',
    `css_config`      TEXT         COMMENT 'css 模式：Animeko web-selector 的 searchConfig(JSON)',
    `mac_cms_api`     VARCHAR(500) COMMENT 'maccms 采集接口根地址，如 https://cj.lziapi.com',
    `browser_resolve` TINYINT      DEFAULT 0 COMMENT '播放页直链是否需无头浏览器（1=是）',
    `match_video_url` VARCHAR(1000) COMMENT '浏览器拦截识别视频的正则（学 Animeko matchVideoUrl）',
    `resolve_js`      TEXT         COMMENT '取直链可执行脚本(预留)',
    `user_agent`      VARCHAR(500),
    `referer`         VARCHAR(500),
    `anti_crawler`    TEXT         COMMENT '反爬配置(JSON)',
    `weight`          INT          DEFAULT 0 COMMENT '聚合排序权重，越大越优先',
    `enabled`         TINYINT      DEFAULT 1,
    `health_status`   VARCHAR(20)  DEFAULT 'UNKNOWN' COMMENT 'UP/DEGRADED/DOWN/UNKNOWN',
    `success_rate`    DOUBLE       DEFAULT 0,
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY `uk_name` (`name`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='采集规则表';

-- ── 规则1（默认可用）：maccms 采集接口 ────────────────────────
-- 一次调用即拿到「番剧 + 分集 + 明文 m3u8 直链」，无需浏览器。已实测可跑通闭环。
INSERT INTO `source_rule`
  (`name`, `version`, `base_url`, `search_mode`, `chapter_mode`, `mac_cms_api`,
   `user_agent`, `referer`, `weight`, `enabled`)
VALUES
  ('量子资源(cj.lziapi.com)', '1.0', 'https://cj.lziapi.com', 'maccms', 'xpath', 'https://cj.lziapi.com',
   'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36',
   'https://cj.lziapi.com', 20, 1);

-- ── 规则2：7sefun（XPath 抓网页 + 无头浏览器取直链）──────────────
-- 已实测：搜索 12 条 → 详情 720 集 → 浏览器拦截到真 mp4 直链。播放页直链需浏览器（browser_resolve=1）。
INSERT INTO `source_rule`
  (`name`, `version`, `base_url`, `search_url`, `use_post`,
   `search_mode`, `chapter_mode`, `search_list`, `search_name`, `search_result`,
   `chapter_roads`, `chapter_result`, `browser_resolve`, `match_video_url`,
   `user_agent`, `referer`, `weight`, `enabled`)
VALUES
  ('7sefun(七色动漫)', '1.0', 'https://www.7sefun.top', '/vodsearch/-------------.html?wd=@keyword', 0,
   'xpath', 'xpath',
   '//a[contains(@class,''video-wrapper'')]',
   './/img/@alt',
   '@href',
   '//div[contains(@class,''vod-play-list-container'')]',
   './/a',
   1, NULL,
   'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
   'https://www.7sefun.top', 10, 1);

-- ═══════════════════════════════════════════════════════════════
-- 如何导入 Kazumi 规则库（几十个 XPath 规则，直接可用）：
--   仓库：https://github.com/Predidit/KazumiRules
--   字段映射：
--     Kazumi.baseURL       -> base_url
--     Kazumi.searchURL     -> search_url   （@keyword 占位符一致）
--     Kazumi.usePost       -> use_post
--     Kazumi.searchMode    -> search_mode  （xpath/api）
--     Kazumi.searchList    -> search_list
--     Kazumi.searchName    -> search_name
--     Kazumi.searchResult  -> search_result
--     Kazumi.chapterRoads  -> chapter_roads
--     Kazumi.chapterResult -> chapter_result
--     Kazumi.userAgent     -> user_agent
--     Kazumi.referer       -> referer
--   注意：Kazumi 的 searchName/searchResult 常是「元素选择器」（如 //a、//div[2]/text()），
--        本引擎已按元素/文本两种节点处理；导入后建议先用 /api/source/test 验证。
-- ═══════════════════════════════════════════════════════════════
