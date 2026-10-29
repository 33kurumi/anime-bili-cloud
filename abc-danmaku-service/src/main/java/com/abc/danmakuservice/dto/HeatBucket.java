package com.abc.danmakuservice.dto;

/**
 * 弹幕热度分桶（"弹幕密度曲线"）。
 *
 * <p>用途：播放器进度条上画出"哪一段弹幕最多"的密度条（类似 B 站进度条上的弹幕高峰）。
 * 前端拿到桶数组后，把 count 归一化成高度即可。
 *
 * @param index     桶序号（从 0 开始，index * bucketSeconds = 起始秒）
 * @param startTime 桶起始时间（秒）
 * @param endTime   桶结束时间（秒）
 * @param count     桶内弹幕条数
 */
public record HeatBucket(int index, double startTime, double endTime, int count) {
}
