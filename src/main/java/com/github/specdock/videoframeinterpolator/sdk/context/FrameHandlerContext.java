package com.github.specdock.videoframeinterpolator.sdk.context;

import com.github.specdock.videoframeinterpolator.sdk.handler.FrameHandler;

/**
 * @author specdock
 * @Date 2026/3/25
 * @Time 20:05
 */
public interface FrameHandlerContext {
        FrameHandler handler();

        FrameHandlerContext handler(FrameHandler handler);

        FrameHandlerContext next();

        FrameHandlerContext next(FrameHandlerContext ctx);

        FrameHandlerContext fireFrameRead(Object msg);

        FrameHandlerContext fireStop();

        FrameHandlerContext fireStart();
}
