package com.github.specdock.videoframeinterpolator.sdk.pipeline;

import com.github.specdock.videoframeinterpolator.sdk.handler.FrameHandler;
import com.github.specdock.videoframeinterpolator.sdk.listener.VideoProcessListener;

/**
 * @author specdock
 * @Date 2026/3/25
 * @Time 20:00
 */
public interface FramePipeline {
    FramePipeline addLast(FrameHandler frameHandler);
    FramePipeline fireFrameRead(Object msg);
    FramePipeline setProgressListener(VideoProcessListener listener);
}
