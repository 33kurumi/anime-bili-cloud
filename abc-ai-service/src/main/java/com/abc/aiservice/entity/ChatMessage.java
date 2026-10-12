package com.abc.aiservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_chat_message")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    private String role;      // system/user/assistant
    private String content;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
