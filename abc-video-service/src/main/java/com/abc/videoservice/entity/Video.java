package com.abc.videoservice.entity;

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
@TableName("video")
public class Video {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long episodeId;

    private String videoUrl;         // 播放地址

    private Long fileSize;           // 文件大小(bytes)

    private String quality;          // 清晰度: 360P/720P/1080P

    private Long duration;           // 视频时长(秒)

    private String format;           // 视频格式: mp4/m3u8

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
