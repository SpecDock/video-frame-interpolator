package com.github.specdock.videoframeinterpolator.sdk.msg;

import org.bytedeco.javacv.Frame;

/**
 * 帧对消息载荷 (用于 Decoder -> Interpolator)
 * 架构升级：废弃 JVM 堆内 BufferedImage，采用底层 Frame 物理指针直通，杜绝色彩空间降维。
 */
public record FramePairMessage(
        String videoId,
        String originalVideoPath,
        String targetOutputDirectory,
        Frame frame1,           // 前置原生物理帧
        Frame frame2,           // 后置原生物理帧
        boolean isEof,          // 边界标识：是否为视频的最后一帧
        double originalFps,     // 原始帧率
        int width,
        int height,
        long totalTargetFrames  // 目标总帧数 (由 Decoder 提取，供下游进度条计算使用)
) {}