package com.abc.aiservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_chat_session")
public class ChatSession {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 本轮 RAG 上下文快照（截断 4000 字）。
     * 答辩演示「AI 为什么推荐这部」时可回溯，也便于排查 Feign 取数是否真的通了。
     */
    private String ragSnapshot;
}