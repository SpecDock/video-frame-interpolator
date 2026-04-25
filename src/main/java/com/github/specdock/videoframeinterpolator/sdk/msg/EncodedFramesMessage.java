package com.github.specdock.videoframeinterpolator.sdk.msg;

import org.bytedeco.javacv.Frame;
import java.util.List;

/**
 * 待编码帧消息载荷 (用于 Interpolator -> Encoder)
 * 架构升级：承载原帧与插值帧的底层 Native 内存引用列表。
 */
public record EncodedFramesMessage(
        String videoId,
        String originalVideoPath,
        String targetOutputDirectory,
        List<Frame> framesToEncode, // 包含原帧与插值帧的物理指针列表
        boolean isEof,
        double targetFps,
        int width,
        int height
) {}