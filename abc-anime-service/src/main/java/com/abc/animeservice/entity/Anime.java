package com.abc.animeservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("anime")
public class Anime implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;            // 番剧名称
    private String coverUrl;         // 封面图URL
    private String description;      // 剧情简介
    private String type;             // TV / MOVIE / OVA / ONA / SPECIAL
    private String status;           // 连载中 / 已完结 / 未开播
    private Integer year;            // 播出年份
    private String season;           // 播出季度: 1月/4月/7月/10月
    private Integer episodesCount;   // 总集数
    private Double ratingAvg;        // 平均评分
    private Long viewCount;          // 总播放量
    private String bangumiId;        // Bangumi ID (for future integration)
    private String studio;           // 动画制作公司

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT, insertStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
