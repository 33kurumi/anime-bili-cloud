package com.abc.commentservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("comment")
public class Comment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String username;         // denormalized for display
    private String animeId;
    private Long episodeId;          // nullable — comment on anime level or episode level
    private String content;
    private Long parentId;           // for nested replies (楼中楼), null = top-level
    private Long replyToUserId;      // who is being replied to
    private String replyToUsername;
    private Integer likeCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
