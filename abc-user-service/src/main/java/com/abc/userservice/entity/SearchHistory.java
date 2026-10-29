package com.abc.userservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户搜索历史（对应 sql/03_platform_extension.sql 的 search_history 表）。
 *
 * <p>norm_keyword 为归一化后的关键词（全角转半角、去标点、转小写），
 * 用于「搜索联想」与「AI 画像」去重，避免 " 进击的巨人 " 和 "进击的巨人" 被当成两个词。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("search_history")
public class SearchHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String keyword;

    private String normKeyword;

    private Integer resultCount;

    /** 搜索来源：WEB / AI / BANGUMI */
    private String source;

    private LocalDateTime createTime;
}
