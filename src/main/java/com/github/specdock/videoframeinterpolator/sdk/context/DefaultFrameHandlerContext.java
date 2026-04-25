package com.github.specdock.videoframeinterpolator.sdk.context;

import com.github.specdock.videoframeinterpolator.sdk.handler.FrameHandler;

/**
 * @author specdock
 * @Date 2026/3/25
 * @Time 20:07
 */
public class DefaultFrameHandlerContext implements FrameHandlerContext {
    private FrameHandlerContext next;
    private FrameHandler handler;

    public DefaultFrameHandlerContext(FrameHandler handler, FrameHandlerContext next) {
        this.handler = handler;
        this.next = next;
    }

    @Override
    public FrameHandlerContext fireStop() {
        next.handler().stop(next);
        return this;
    }

    @Override
    public FrameHandlerContext fireStart() {
        next.handler().start(next);
        return this;
    }

    @Override
    public FrameHandler handler(){
        return handler;
    }

    @Override
    public FrameHandlerContext handler(FrameHandler handler) {
        this.handler = handler;
        return this;
    }

    @Override
    public FrameHandlerContext next(){
        return next;
    }

    @Override
    public FrameHandlerContext next(FrameHandlerContext ctx){
        this.next = ctx;
        return this;
    }



    @Override
    public FrameHandlerContext fireFrameRead(Object msg) {
        next.handler().frameRead(next, msg);
        return this;
    }



}
