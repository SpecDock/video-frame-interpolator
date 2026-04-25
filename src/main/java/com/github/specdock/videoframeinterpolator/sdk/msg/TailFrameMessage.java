package com.github.specdock.videoframeinterpolator.sdk.msg;

import org.bytedeco.javacv.Frame;

import java.util.List;

/**
 * @author specdock
 * @Date 2026/3/26
 * @Time 21:55
 */
public record TailFrameMessage (
        String videoId,
        String originalVideoPath,
        String targetOutputVideoPath,
        boolean isEof,
        long currentFrameIndex,
        long totalFrames
)
{}
