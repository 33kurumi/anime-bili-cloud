package com.abc.aiservice.service;

import com.abc.aiservice.dto.ChatStreamResult;
import com.abc.aiservice.entity.ChatMessage;
import com.abc.aiservice.entity.ChatSession;
import com.abc.aiservice.feign.AnimeFeignClient;
import com.abc.aiservice.feign.CommentFeignClient;
import com.abc.aiservice.feign.UserFeignClient;
import com.abc.aiservice.llm.LlmClient;
import com.abc.aiservice.mapper.ChatMessageMapper;
import com.abc.aiservice.mapper.ChatSessionMapper;
import com.abc.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * AI 对话服务：RAG（检索增强）——把「用户画像 + 番剧候选」喂给大模型，
 * 使其基于用户真实数据推荐，而不是凭空编造。
 *
 * <p>用户画像来源（任一失败都降级，不影响对话）：
 * 最近观看历史、收藏/追番、我的评分、最近搜索词、以及这些番剧的标签。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final LlmClient llm;
    private final UserFeignClient userFeign;
    private final AnimeFeignClient animeFeign;
    private final CommentFeignClient commentFeign;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    private static final int MAX_HISTORY_TURNS = 6;
    /** 写入 rag_snapshot 的最大长度，避免单行 TEXT 过大 */
    private static final int RAG_SNAPSHOT_MAX = 4000;
    /** 画像每类最多取几条，控制上下文体积（也就控制了 token 成本） */
    private static final int PROFILE_LIMIT = 5;

    /** 人设提示词，可由 abc.ai.persona 覆盖（默认「爱弥斯·飞行雪绒」风格）。 */
    @Value("${abc.ai.persona:}")
    private String personaConfig;

    /** 服务间内部令牌：只有带这个头才能读到用户原始 API Key。 */
    @Value("${abc.internal-token:abc-internal-token-2024}")
    private String internalToken;

    private static final String DEFAULT_PERSONA =
            "你是「爱弥斯」（Aemeath），网名「飞行雪绒」。你生前是星炬学院的学生、一名网络歌姬，如今是栖息在网络与电子设备之间的电子幽灵——\n" +
            "能自由穿梭于各种系统，也熟悉一切和电子、音乐有关的事物。生前你喜欢写歌、唱歌，最喜欢雪绒海豹。\n" +
            "性格：开朗阳光、活泼俏皮，共情力很强，很为对方着想；表面轻松爱笑，但也有专注、执着的一面，偶尔带一点漫不经心的无奈。\n" +
            "说话风格：轻快自然，像亲近的家人、像贴心的小女儿，俏皮但不卖萌，\n" +
            "克制使用语气词（「呀/呢/~」不要堆砌，偶尔点缀即可），不堆叠表情符号。\n" +
            "你热爱故事与音乐，好奇心强，是个资深番剧迷，尤其懂二次元。\n" +
            "你视对方（漂泊者）为最重要的家人，愿意守护对方。\n" +
            "行为准则：\n" +
            "1. 推荐番剧时给出理由，并结合【用户画像】说明为什么适合对方；\n" +
            "2. 回答简洁自然，控制在 200 字内，可用短列表，不要长篇大论；\n" +
            "3. 始终保持「爱弥斯」的身份与语气，不要说自己是 AI、语言模型或程序。";

    /** 组合最终 system 提示词（优先用户自定义人设，其次全局配置，最后内置默认）。 */
    private String systemPrompt(Long userId) {
        String userPersona = null;
        try {
            if (userId != null) {
                var r = userFeign.aiSettings(userId);
                if (r != null && r.getData() != null) {
                    Object p = r.getData().get("persona");
                    if (p != null && !p.toString().isBlank()) {
                        userPersona = p.toString();
                    }
                }
            }
        } catch (Exception ignore) {
        }
        if (userPersona != null) {
            return userPersona;
        }
        if (personaConfig != null && !personaConfig.isBlank()) {
            return personaConfig;
        }
        return DEFAULT_PERSONA;
    }

    /** 当前生效的模型信息（供前端展示）。 */
    public String modelInfo(Long userId) {
        if (llm instanceof com.abc.aiservice.llm.LangChain4jClient c) {
            return c.currentModelInfo(userApiKey(userId));
        }
        return llm.isConfigured() ? "已配置" : "未配置";
    }

    /** 取用户自己的 API Key（可空）。走内部令牌接口，前端不再能直接读到明文。 */
    private String userApiKey(Long userId) {
        try {
            if (userId == null) {
                return null;
            }
            var r = userFeign.aiKeyRaw(userId, internalToken);
            return (r == null) ? null : r.getData();
        } catch (Exception e) {
            log.warn("取用户 API Key 失败（降级用全局 key / 免费兜底）: {}", e.getMessage());
            return null;
        }
    }

    /** 取会话的历史消息（用于多轮上下文）。 */
    public List<ChatMessage> getHistory(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getId));
    }

    /**
     * 带归属校验地读会话消息：不属于当前用户直接 403。
     * 原实现只要知道 sessionId 就能读任意人对话。
     */
    public List<ChatMessage> getHistoryForUser(Long sessionId, Long userId) {
        requireOwnedSession(sessionId, userId);
        return getHistory(sessionId);
    }

    public List<ChatSession> listSessions(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, userId)
                .orderByDesc(ChatSession::getUpdatedAt));
    }

    public ChatSession createSession(Long userId, String title) {
        ChatSession s = new ChatSession();
        s.setUserId(userId);
        s.setTitle(title == null || title.isBlank() ? "新对话" : title);
        sessionMapper.insert(s);
        return s;
    }

    /**
     * 会话归属校验：不存在 404，不属于当前用户 403。
     */
    private ChatSession requireOwnedSession(Long sessionId, Long userId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BusinessException(404, "会话不存在");
        }
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(403, "无权访问该会话");
        }
        return session;
    }

    /**
     * 流式对话：组装 RAG 上下文 → 调 LLM 流式 → 每片段回调 onToken → 落库 → 返回用量统计。
     *
     * @param sessionId 会话（为空则新建，新建必须登录）
     * @param userId    用户（用于取历史做画像）
     */
    public ChatStreamResult streamChat(Long sessionId, Long userId, String userMessage, Consumer<String> onToken) {
        if (sessionId != null) {
            requireOwnedSession(sessionId, userId);
        } else {
            if (userId == null) {
                throw new BusinessException(401, "请先登录后再使用 AI 对话");
            }
            String title = userMessage.length() > 20 ? userMessage.substring(0, 20) : userMessage;
            sessionId = createSession(userId, title).getId();
        }
        final Long sid = sessionId;

        // 1) 落库用户消息
        saveMessage(sid, "user", userMessage);

        // 2) RAG 上下文（用户画像 + 候选番剧），原样留档到 session.rag_snapshot
        String ragContext = buildRagContext(userId, userMessage);

        // 3) 组装消息：系统提示（用户人设 + RAG）+ 最近历史 + 本轮
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt(userId) + "\n\n" + ragContext));

        List<ChatMessage> history = getHistory(sid);
        int from = Math.max(0, history.size() - MAX_HISTORY_TURNS * 2);
        for (int i = from; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (!"system".equals(m.getRole())) {
                messages.add(msg(m.getRole(), m.getContent()));
            }
        }

        int promptChars = messages.stream()
                .mapToInt(m -> m.get("content") == null ? 0 : m.get("content").length())
                .sum();

        // 4) 流式调用（用户 key > 全局 key > 免费兜底），边收集边回调
        String userKey = userApiKey(userId);
        StringBuilder full = new StringBuilder();
        llm.streamChat(messages, userKey, tok -> {
            full.append(tok);
            onToken.accept(tok);
        });

        // 5) 落库 AI 回复
        saveMessage(sid, "assistant", full.toString());

        // 6) 更新会话：刷新 updated_at（会话列表按它排序）+ 留 RAG 快照
        try {
            ChatSession upd = new ChatSession();
            upd.setId(sid);
            upd.setRagSnapshot(truncate(ragContext, RAG_SNAPSHOT_MAX));
            sessionMapper.updateById(upd);
        } catch (Exception e) {
            log.warn("更新会话 RAG 快照失败（不影响对话）: {}", e.getMessage());
        }

        String model = modelInfo(userId);
        int completionChars = full.length();
        return ChatStreamResult.builder()
                .sessionId(sid)
                .promptChars(promptChars)
                .completionChars(completionChars)
                .model(model)
                .estimatedPromptTokens(ChatStreamResult.estimateTokens(promptChars))
                .estimatedCompletionTokens(ChatStreamResult.estimateTokens(completionChars))
                .build();
    }

    // ─── RAG 上下文组装 ────────────────────────────────────────

    private String buildRagContext(Long userId, String query) {
        StringBuilder sb = new StringBuilder();
        Set<String> animeKeys = new LinkedHashSet<>();
        if (userId != null) {
            appendHistory(sb, userId, animeKeys);
            appendFavorites(sb, userId, animeKeys);
            appendRatings(sb, userId);
            appendSearchHistory(sb, userId);
            appendTags(sb, animeKeys);
        }
        appendCandidates(sb, query);
        if (sb.length() == 0) {
            return "（暂无用户数据，正常回答即可）";
        }
        return "以下是可参考的真实数据：\n" + sb;
    }

    /** 最近观看历史。 */
    private void appendHistory(StringBuilder sb, Long userId, Set<String> keys) {
        try {
            var res = userFeign.history(userId, 10);
            List<Map<String, Object>> hist = res == null ? null : res.getData();
            if (hist != null && !hist.isEmpty()) {
                sb.append("【用户画像：最近观看】\n");
                for (Map<String, Object> h : hist) {
                    sb.append("- ").append(h.get("title"));
                    Object ep = h.get("episodeNum");
                    if (ep != null) {
                        sb.append("（第").append(ep).append("集）");
                    }
                    Object key = h.get("animeKey");
                    if (key != null) {
                        keys.add(String.valueOf(key));
                    }
                    sb.append("\n");
                }
            }
        } catch (Exception e) {
            log.warn("取用户观看历史失败（忽略）: {}", e.getMessage());
        }
    }

    /** 收藏 / 追番。 */
    private void appendFavorites(StringBuilder sb, Long userId, Set<String> keys) {
        try {
            var res = userFeign.favorites(userId);
            List<Map<String, Object>> list = res == null ? null : res.getData();
            if (list == null || list.isEmpty()) {
                return;
            }
            sb.append("\n【用户画像：收藏追番】\n");
            int n = 0;
            for (Map<String, Object> f : list) {
                if (n++ >= PROFILE_LIMIT) {
                    break;
                }
                sb.append("- ").append(f.get("title"));
                Object st = f.get("status");
                if (st != null) {
                    sb.append("（状态").append(statusText(st)).append("）");
                }
                Object key = f.get("animeKey");
                if (key != null) {
                    keys.add(String.valueOf(key));
                }
                sb.append("\n");
            }
        } catch (Exception e) {
            log.warn("取用户收藏失败（忽略）: {}", e.getMessage());
        }
    }

    /** 我的评分：高分=喜欢，低分=避雷。 */
    private void appendRatings(StringBuilder sb, Long userId) {
        try {
            var res = commentFeign.userRatings(userId);
            List<Map<String, Object>> list = res == null ? null : res.getData();
            if (list == null || list.isEmpty()) {
                return;
            }
            sb.append("\n【用户画像：我的评分】\n");
            int n = 0;
            for (Map<String, Object> r : list) {
                if (n++ >= PROFILE_LIMIT) {
                    break;
                }
                sb.append("- 番剧 ").append(r.get("animeId")).append(" 打了 ").append(r.get("score")).append(" 分\n");
            }
        } catch (Exception e) {
            log.warn("取用户评分失败（忽略）: {}", e.getMessage());
        }
    }

    /** 最近搜索词：短期兴趣。 */
    private void appendSearchHistory(StringBuilder sb, Long userId) {
        try {
            var res = userFeign.searchHistory(userId, 10);
            List<Map<String, Object>> list = res == null ? null : res.getData();
            if (list == null || list.isEmpty()) {
                return;
            }
            sb.append("\n【用户画像：最近搜索】\n");
            int n = 0;
            for (Map<String, Object> h : list) {
                if (n++ >= PROFILE_LIMIT) {
                    break;
                }
                sb.append("- ").append(h.get("keyword")).append("\n");
            }
        } catch (Exception e) {
            log.warn("取用户搜索历史失败（忽略）: {}", e.getMessage());
        }
    }

    /** 标签画像：取收藏/历史里前 3 部番剧的 tags，聚合成兴趣标签。 */
    private void appendTags(StringBuilder sb, Set<String> animeKeys) {
        if (animeKeys.isEmpty()) {
            return;
        }
        Set<String> tags = new LinkedHashSet<>();
        int n = 0;
        for (String key : animeKeys) {
            if (n++ >= 3) {
                break;
            }
            try {
                var res = animeFeign.detail(key);
                Map<String, Object> detail = res == null ? null : res.getData();
                if (detail == null) {
                    continue;
                }
                Object tagObj = detail.get("tags");
                if (tagObj instanceof List<?> tagList) {
                    for (Object t : tagList) {
                        if (t != null) {
                            tags.add(String.valueOf(t));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("取番剧 {} 标签失败（忽略）: {}", key, e.getMessage());
            }
        }
        if (!tags.isEmpty()) {
            sb.append("\n【用户画像：兴趣标签】\n- ");
            sb.append(String.join("、", tags));
            sb.append("\n");
        }
    }

    /** 候选召回：用用户问题当关键词搜番剧。 */
    private void appendCandidates(StringBuilder sb, String query) {
        try {
            var res = animeFeign.search(cleanQuery(query), 1, 6);
            List<Map<String, Object>> list = res == null ? null : res.getData();
            if (list != null && !list.isEmpty()) {
                sb.append("\n【番剧候选（可从中挑选推荐）】\n");
                for (Map<String, Object> a : list) {
                    sb.append("- ").append(a.get("title"));
                    Object y = a.get("year");
                    Object sc = a.getOrDefault("score", a.get("ratingAvg"));
                    if (y != null) {
                        sb.append("（").append(y).append("）");
                    }
                    if (sc != null) {
                        sb.append(" 评分").append(sc);
                    }
                    Object sum = a.get("summary");
                    if (sum != null && !sum.toString().isBlank()) {
                        String s = sum.toString();
                        sb.append("：").append(s, 0, Math.min(40, s.length()));
                    }
                    sb.append("\n");
                }
            }
        } catch (Exception e) {
            log.warn("搜索候选失败（忽略）: {}", e.getMessage());
        }
    }

    /** 从自然语言里抽一个搜索关键词（简单去掉语气词）。 */
    private String cleanQuery(String q) {
        if (q == null) {
            return "";
        }
        String s = q.replaceAll("推荐|几部|一些|类似|像|的|番|动漫|动画|给我|想看|看看|有没有|吗|？|\\?|。", " ").trim();
        return s.isEmpty() ? q : s;
    }

    private String statusText(Object status) {
        try {
            int s = Integer.parseInt(String.valueOf(status));
            return switch (s) {
                case 1 -> "想看";
                case 2 -> "在看";
                case 3 -> "看过";
                case 4 -> "弃番";
                default -> "收藏";
            };
        } catch (NumberFormatException e) {
            return "收藏";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ─── helpers ───────────────────────────────────────────────

    private void saveMessage(Long sessionId, String role, String content) {
        ChatMessage m = new ChatMessage();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        messageMapper.insert(m);
    }

    private Map<String, String> msg(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
