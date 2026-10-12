package com.abc.sourceservice.engine.strategy;

import cn.wanghaomiao.xpath.model.JXDocument;
import cn.wanghaomiao.xpath.model.JXNode;
import com.abc.sourceservice.engine.EngineModels.Road;
import com.abc.sourceservice.engine.EngineModels.SearchItem;
import com.abc.sourceservice.engine.RuleException;
import com.abc.sourceservice.engine.UrlUtil;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * XPath 解析策略（对齐 Kazumi xpath_rule_strategy）。
 * 用 Jsoup 解析 HTML，JsoupXpath 执行 XPath。
 */
@Slf4j
public class XpathStrategy {

    /** 阶段一：从搜索页 HTML 解析出 SearchItem 列表。 */
    public static List<SearchItem> parseSearch(String html, String listXp, String nameXp,
                                                String resultXp, String baseUrl, String ruleName) {
        List<SearchItem> items = new ArrayList<>();
        try {
            JXDocument jx = new JXDocument(html);
            List<JXNode> nodes = jx.selN(listXp);
            if (nodes == null || nodes.isEmpty()) return items;

            for (JXNode node : nodes) {
                String name = firstString(node, nameXp, false);
                String url = firstString(node, resultXp, true);
                if (name == null || url == null) continue;
                items.add(new SearchItem(name.trim(), UrlUtil.normalize(baseUrl, url)));
            }
        } catch (Exception e) {
            throw RuleException.searchError(ruleName, e);
        }
        return items;
    }

    /** 阶段二：从详情/播放页 HTML 解析出线路与分集。 */
    public static List<Road> parseChapters(String html, String roadsXp, String resultXp,
                                           String baseUrl, String ruleName) {
        List<Road> roads = new ArrayList<>();
        try {
            JXDocument jx = new JXDocument(html);
            List<JXNode> roadNodes = jx.selN(roadsXp);
            if (roadNodes == null || roadNodes.isEmpty()) return roads;

            int idx = 1;
            for (JXNode roadNode : roadNodes) {
                List<String> urls = new ArrayList<>();
                List<String> names = new ArrayList<>();
                List<JXNode> epNodes = safeSel(roadNode, resultXp);
                for (JXNode ep : epNodes) {
                    String url = extractUrl(ep, baseUrl);
                    String name = extractName(ep);
                    if (url == null) continue;
                    urls.add(url);
                    names.add(name != null ? name : ("第" + (urls.size()) + "集"));
                }
                if (!urls.isEmpty()) {
                    roads.add(new Road("播放线路" + idx++, urls, names));
                }
            }
        } catch (Exception e) {
            throw RuleException.chapterError(ruleName, e);
        }
        return roads;
    }

    // ─── helpers ───────────────────────────────────────────────

    private static List<JXNode> safeSel(JXNode node, String xpath) {
        try {
            List<JXNode> r = node.sel(xpath);
            return r == null ? List.of() : r;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 取节点的第一个匹配结果的字符串值（text 或属性）。 */
    private static String firstString(JXNode ctx, String xpath, boolean preferAttr) {
        List<JXNode> nodes = safeSel(ctx, xpath);
        if (nodes.isEmpty()) return null;
        JXNode n = nodes.get(0);
        String s = valOf(n);
        return (s != null && !s.isBlank()) ? s.trim() : null;
    }

    /** JXNode → 字符串：text/属性节点取 getTextVal()，元素取 text()。 */
    private static String valOf(JXNode n) {
        try {
            if (n.isText()) return n.getTextVal();
            Element el = n.getElement();
            return el != null ? el.text() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从分集节点提取 URL：元素取 href/src，文本/属性节点取其值。 */
    private static String extractUrl(JXNode node, String baseUrl) {
        try {
            if (!node.isText()) {
                Element el = node.getElement();
                if (el != null) {
                    String href = !el.attr("href").isBlank() ? el.attr("href") : el.attr("src");
                    if (!href.isBlank()) return UrlUtil.normalize(baseUrl, href);
                    String txt = el.text();
                    if (txt.startsWith("http")) return txt.trim();
                    return null;
                }
            }
            String s = node.getTextVal();
            if (s != null && !s.isBlank()) return UrlUtil.normalize(baseUrl, s.trim());
        } catch (Exception ignored) { }
        return null;
    }

    /** 从分集节点提取显示名：元素取 title 或 text，文本节点取自身值。 */
    private static String extractName(JXNode node) {
        try {
            if (!node.isText()) {
                Element el = node.getElement();
                if (el != null) {
                    String title = el.attr("title");
                    if (!title.isBlank()) return title.trim();
                    String txt = el.text();
                    if (!txt.isBlank()) return txt.trim();
                }
                return null;
            }
            String s = node.getTextVal();
            return (s != null && !s.isBlank()) ? s.trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 从任意 HTML 中按正则/标签抽取第一个 m3u8/mp4 直链（A 档静态站用）。 */
    public static String extractDirectLink(String html, String baseUrl) {
        if (html == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(https?:)?//[^\"'\\s]+\\.(m3u8|mp4)(\\?[^\"'\\s]*)?")
                .matcher(html);
        if (m.find()) {
            String url = m.group();
            return UrlUtil.normalize(baseUrl, url);
        }
        // 相对路径形式
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("[\"']([^\"'\\s]*\\.(m3u8|mp4)(\\?[^\"'\\s]*)?)[\"']")
                .matcher(html);
        if (m2.find()) {
            return UrlUtil.normalize(baseUrl, m2.group(1));
        }
        return null;
    }

    public static Document parse(String html) {
        return Jsoup.parse(html);
    }
}
