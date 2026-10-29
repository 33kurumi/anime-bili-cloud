package com.abc.userservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户收藏 / 追番（对应 sql/03_platform_extension.sql 的 user_favorite 表）。
 *
 * <p>anime_key 是跨服务的稳定番剧标识：远程番剧 "bgm:400602"，本地番剧 "123"。
 * 状态取值：1 想看 / 2 在看 / 3 看过 / 4 弃番；is_follow=1 表示同时在「追番」列表。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_favorite")
public class UserFavorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String animeKey;

    private Long bangumiId;

    private String title;

    private String coverUrl;

    private Integer status;

    private Integer isFollow;

    private Integer lastEpisode;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
