package com.github.specdock.videoframeinterpolator.sdk.msg;

/**
 * @author specdock
 * @Date 2026/3/26
 * @Time 20:34
 */
public record DecodedFramesMessage (
        String videoId,
        String originalVideoPath,
        String targetOutputDirectory
){}
