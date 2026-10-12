package com.abc.videoservice.controller;

import com.abc.common.result.R;
import com.abc.videoservice.entity.Video;
import com.abc.videoservice.service.VideoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoController {

    private final VideoService videoService;

    @GetMapping("/{episodeId}")
    public R<Map<String, Object>> getPlayInfo(@PathVariable Long episodeId) {
        return R.ok(videoService.getPlayInfo(episodeId));
    }

    @PostMapping("/upload")
    public R<Video> upload(@RequestParam("file") MultipartFile file,
                            @RequestParam("episodeId") Long episodeId) {
        return R.ok(videoService.uploadVideo(file, episodeId));
    }
}
