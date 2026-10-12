package com.abc.userservice.entity;

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
@TableName("user_follow")
public class UserFollow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long followerId;         // 关注者

    private Long followeeId;         // 被关注者

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
