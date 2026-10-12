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
@TableName("rating")
public class Rating {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long animeId;

    private Integer score;           // 1-10

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
