package com.abc.videoservice.service;

import cn.hutool.core.io.FileUtil;
import com.abc.common.exception.BusinessException;
import com.abc.videoservice.entity.Video;
import com.abc.videoservice.mapper.VideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VideoService {

    private final VideoMapper videoMapper;

    @Value("${video.upload.dir:./uploads/videos}")
    private String uploadDir;

    public Map<String, Object> getPlayInfo(Long episodeId) {
        Video video = videoMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Video>()
                        .eq(Video::getEpisodeId, episodeId)
                        .orderByDesc(Video::getCreatedAt)
                        .last("LIMIT 1"));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("episodeId", episodeId);
        if (video != null) {
            info.put("url", video.getVideoUrl());
            info.put("quality", video.getQuality());
            info.put("duration", video.getDuration());
            info.put("format", video.getFormat());
        } else {
            info.put("url", null);
            info.put("quality", "720P");
        }
        return info;
    }

    public Video uploadVideo(MultipartFile file, Long episodeId) {
        // Validate
        if (file.isEmpty()) {
            throw new BusinessException(1501, "文件为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            throw new BusinessException(1501, "文件名无效");
        }
        String ext = FileUtil.extName(originalFilename).toLowerCase();
        if (!ext.matches("mp4|flv|mkv|webm|m3u8")) {
            throw new BusinessException(1503, "不支持的视频格式: " + ext);
        }

        // Save file
        String newFileName = UUID.randomUUID() + "." + ext;
        File destDir = new File(uploadDir);
        if (!destDir.exists()) destDir.mkdirs();
        File dest = new File(destDir, newFileName);
        try {
            file.transferTo(dest);
        } catch (IOException e) {
            throw new BusinessException(1501, "文件保存失败: " + e.getMessage());
        }

        // Create video record
        Video video = Video.builder()
                .episodeId(episodeId)
                .videoUrl("/uploads/videos/" + newFileName)
                .fileSize(file.getSize())
                .quality("720P")
                .format(ext)
                .createdAt(LocalDateTime.now())
                .build();
        videoMapper.insert(video);

        log.info("Video uploaded: episodeId={}, file={}", episodeId, newFileName);
        return video;
    }
}
