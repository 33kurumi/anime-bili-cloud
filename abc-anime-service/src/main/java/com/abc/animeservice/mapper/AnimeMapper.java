package com.abc.animeservice.mapper;

import com.abc.animeservice.entity.Anime;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnimeMapper extends BaseMapper<Anime> {
}
