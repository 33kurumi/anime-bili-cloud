package com.abc.aiservice.service;

import com.abc.aiservice.entity.ChatMessage;
import com.abc.aiservice.entity.ChatSession;
import com.abc.aiservice.feign.AnimeFeignClient;
import com.abc.aiservice.feign.UserFeignClient;
import com.abc.aiservice.llm.LlmClient;
import com.abc.aiservice.mapper.ChatMessageMapper;
import com.abc.aiservice.mapper.ChatSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * AI 对话服务：RAG（检索增强）——把用户观看历史 + 番剧候选喂给大模型，
 * 使其"基于用户真实数据"推荐，而非凭空编造。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final LlmClient llm;
    private final UserFeignClient userFeign;
    private final AnimeFeignClient animeFeign;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    private static final int MAX_HISTORY_TURNS = 6;

    /** 人设提示词，可由 abc.ai.persona 覆盖（默认「爱弥斯·飞行雪绒」风格）。 */
    @org.springframework.beans.factory.annotation.Value("${abc.ai.persona:}")
    private String personaConfig;

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
                    if (p != null && !p.toString().isBlank()) userPersona = p.toString();
                }
            }
        } catch (Exception ignore) { }
        if (userPersona != null) return userPersona;
        if (personaConfig != null && !personaConfig.isBlank()) return personaConfig;
        return DEFAULT_PERSONA;
    }

    /** 当前生效的模型信息（供前端展示）。 */
    public String modelInfo(Long userId) {
        if (llm instanceof com.abc.aiservice.llm.LangChain4jClient c) {
            return c.currentModelInfo(userApiKey(userId));
        }
        return llm.isConfigured() ? "已配置" : "未配置";
    }

    /** 取用户自己的 API Key（可空）。 */
    private String userApiKey(Long userId) {
        try {
            if (userId == null) return null;
            var r = userFeign.aiKey(userId);
            return (r == null) ? null : r.getData();
        } catch (Exception e) {
            return null;
        }
    }

    /** 取会话的历史消息（用于多轮上下文）。 */
    public List<ChatMessage> getHistory(Long sessionId) {
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, sessionId)
                .orderByAsc(ChatMessage::getId));
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
     * 流式对话：组装 RAG 上下文 → 调 LLM 流式 → 每片段回调 onToken → 落库。
     * @param sessionId 会话（为空则新建）
     * @param userId 用户（用于取历史做画像）
     * @return 实际使用的 sessionId
     */
    public Long streamChat(Long sessionId, Long userId, String userMessage, Consumer<String> onToken) {
        if (sessionId == null) sessionId = createSession(userId, userMessage.length() > 20 ? userMessage.substring(0, 20) : userMessage).getId();
        final Long sid = sessionId;

        // 1) 落库用户消息
        saveMessage(sid, "user", userMessage);

        // 2) 组装消息：系统提示（用户人设）+ RAG 上下文 + 历史 + 本轮
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(msg("system", systemPrompt(userId) + "\n\n" + buildRagContext(userId, userMessage)));

        List<ChatMessage> history = getHistory(sid);
        // 只带最近 N 轮（避免 token 过长）
        int from = Math.max(0, history.size() - MAX_HISTORY_TURNS * 2);
        for (int i = from; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            if (!"system".equals(m.getRole())) messages.add(msg(m.getRole(), m.getContent()));
        }

        // 3) 流式调用（用户 key > 全局 key > 免费兜底），边收集边回调
        String userKey = userApiKey(userId);
        StringBuilder full = new StringBuilder();
        llm.streamChat(messages, userKey, tok -> {
            full.append(tok);
            onToken.accept(tok);
        });

        // 4) 落库 AI 回复
        saveMessage(sid, "assistant", full.toString());
        return sid;
    }

    // ─── RAG 上下文组装 ────────────────────────────────────────

    private String buildRagContext(Long userId, String query) {
        StringBuilder sb = new StringBuilder();

        // 用户画像：最近观看历史
        try {
            if (userId != null) {
                var res = userFeign.history(userId, 10);
                List<Map<String, Object>> hist = res.getData();
                if (hist != null && !hist.isEmpty()) {
                    sb.append("【用户画像：最近观看】\n");
                    for (Map<String, Object> h : hist) {
                        sb.append("- ").append(h.get("title")).append("（第").append(h.get("episodeNum")).append("集）\n");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("取用户历史失败（忽略）: {}", e.getMessage());
        }

        // 候选召回：用用户问题当关键词搜番剧
        try {
            var res = animeFeign.search(cleanQuery(query), 1, 6);
            List<Map<String, Object>> list = res.getData();
            if (list != null && !list.isEmpty()) {
                sb.append("\n【番剧候选（可从中挑选推荐）】\n");
                for (Map<String, Object> a : list) {
                    sb.append("- ").append(a.get("title"));
                    Object y = a.get("year");
                    Object sc = a.getOrDefault("score", a.get("ratingAvg"));
                    if (y != null) sb.append("（").append(y).append("）");
                    if (sc != null) sb.append(" 评分").append(sc);
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

        if (sb.length() == 0) return "（暂无用户数据，正常回答即可）";
        return "以下是可参考的真实数据：\n" + sb;
    }

    /** 从自然语言里抽一个搜索关键词（简单去掉语气词）。 */
    private String cleanQuery(String q) {
        if (q == null) return "";
        String s = q.replaceAll("推荐|几部|一些|类似|像|的|番|动漫|动画|给我|想看|看看|有没有|吗|？|\\?|。", " ").trim();
        return s.isEmpty() ? q : s;
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
