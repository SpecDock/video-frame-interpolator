package com.github.specdock.videoframeinterpolator.sdk.handler;

import com.github.specdock.videoframeinterpolator.sdk.context.FrameHandlerContext;

/**
 * @author specdock
 * @Date 2026/3/25
 * @Time 19:59
 */
public interface FrameHandler {
    void frameRead(FrameHandlerContext ctx, Object msg);
    void stop(FrameHandlerContext ctx);

    void start(FrameHandlerContext next);
}
