package com.abc.userservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户播放进度（对应 sql/03_platform_extension.sql 的 user_progress 表）。
 *
 * <p>与 watch_history 的区别：watch_history 是「看过什么」的流水（按集），
 * user_progress 是「看到第几秒」的断点续播数据，支持多端增量同步（since 游标）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_progress")
public class UserProgress {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String animeKey;

    private Integer episodeNum;

    private Integer positionSec;

    private Integer durationSec;

    /** 0 未看完 / 1 已看完 */
    private Integer finished;

    /** web / android / ios，多端同步时区分来源 */
    private String device;

    private LocalDateTime updateTime;
}
