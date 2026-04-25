package com.github.specdock.videoframeinterpolator.sdk.interfaces;

/**
 * @author specdock
 * @Date 2026/3/25
 * @Time 13:47
 */
public interface VideoFrameInterpolator {
    String storeVideo(String videoPath);

    String interpolateFrames(String videoId, int targetFrameRate);
}
