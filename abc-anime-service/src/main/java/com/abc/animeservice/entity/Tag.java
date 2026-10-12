package com.abc.animeservice.entity;

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
@TableName("tag")
public class Tag {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;             // 标签名: 热血/搞笑/恋爱/异世界/治愈...

    private String type;             // category / genre

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
