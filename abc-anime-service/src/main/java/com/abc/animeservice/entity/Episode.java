package com.abc.animeservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("episode")
public class Episode {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long animeId;

    private String title;            // 单集标题
    private Integer episodeNum;      // 第几集
    private String videoUrl;         // 视频播放地址
    private Long duration;           // 时长(秒)
    private Long playCount;          // 播放量

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT, insertStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
