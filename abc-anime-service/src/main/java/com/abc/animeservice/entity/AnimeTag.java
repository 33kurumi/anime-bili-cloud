package com.abc.animeservice.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("anime_tag")
public class AnimeTag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long animeId;

    private Long tagId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
