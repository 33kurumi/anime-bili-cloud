package com.abc.danmakuservice.repository;

import com.abc.danmakuservice.entity.Danmaku;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DanmakuRepository extends MongoRepository<Danmaku, String> {

    /**
     * Find danmaku for a specific episode (unified key), ordered by video timestamp.
     * Used when a user first opens a video.
     */
    List<Danmaku> findByAnimeKeyAndEpisodeNumOrderByTimestampAsc(String animeKey, Integer episodeNum);

    /** Whether this episode already has any pulled danmaku (to avoid repeated remote fetch). */
    boolean existsByAnimeKeyAndEpisodeNum(String animeKey, Integer episodeNum);

    /** Idempotent pull: avoid importing the same upstream danmaku twice. */
    boolean existsByAnimeKeyAndEpisodeNumAndSourceAndTextAndTimestamp(
            String animeKey, Integer episodeNum, String source, String text, Double timestamp);

    /** 某用户发的弹幕（个人中心"我的弹幕"）。 */
    List<Danmaku> findByUsernameAndSourceOrderBySendTimeDesc(String username, String source);
}
