package com.github.specdock.videoframeinterpolator.sdk.handler.codec;

import com.github.specdock.videoframeinterpolator.sdk.context.FrameHandlerContext;
import com.github.specdock.videoframeinterpolator.sdk.eventloop.VideoEventLoop;
import com.github.specdock.videoframeinterpolator.sdk.handler.FrameHandler;
import com.github.specdock.videoframeinterpolator.sdk.msg.DecodedFramesMessage;
import com.github.specdock.videoframeinterpolator.sdk.msg.FramePairMessage;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.util.UUID;

/**
 * @author specdock
 *
 * 异步状态机视频解码器 (原生物理编码直通版)
 * 彻底废弃 BufferedImage，采用底层 Frame (AVFrame) 物理内存流转，杜绝 YUV->BGR 色度降采样损耗。
 */
public class VideoFrameDecoder implements FrameHandler {
    private volatile boolean isRunning = false;

    @Override
    public void stop(FrameHandlerContext ctx) {
        synchronized (this){
            isRunning = false;
        }
        ctx.fireStop();
    }

    @Override
    public void start(FrameHandlerContext ctx) {
        synchronized (this){
            isRunning = true;
            notifyAll();
        }
        ctx.fireStart();
    }

    @Override
    public void frameRead(FrameHandlerContext ctx, Object msg) {
        System.out.println("VideoFrameDecoder：启动原生物理帧提取...");
        if (!(msg instanceof DecodedFramesMessage decodedFramesMessage)) {
            ctx.fireFrameRead(msg);
            return;
        }

        String originalVideoPath = decodedFramesMessage.originalVideoPath();
        String targetOutputDirectory = decodedFramesMessage.targetOutputDirectory();

        // 废弃 Java2DFrameConverter，直接操作底层 Grabber
        try (var grabber = new FFmpegFrameGrabber(originalVideoPath)) {

            grabber.start();
            int width = grabber.getImageWidth();
            int height = grabber.getImageHeight();
            double originalFps = grabber.getFrameRate();
            // 提取总帧数用于进度条
            long totalTargetFrames = grabber.getLengthInVideoFrames() * 2L;

            Frame prevFrame = null;
            Frame grabbedFrame;

            while ((grabbedFrame = grabber.grabImage()) != null) {

                while(!VideoEventLoop.running){
                    System.out.println("VideoEventLoop 未运行，解码器进入等待状态...");
                    synchronized (VideoEventLoop.class) {
                        System.out.println(VideoEventLoop.running);
                        VideoEventLoop.class.wait();
                    }
                }

                // 【核心工程陷阱】：FFmpeg 底层会复用同一个 AVFrame 的物理内存地址。
                // 如果不执行 clone()，下游队列中所有的帧最终都会变成最后一帧的画面。
                // clone() 会在 C++ 堆外内存中安全地开辟一块新空间，这等同于 Netty 中的 ByteBuf.copy()
                Frame currFrame = grabbedFrame.clone();

                if (prevFrame != null) {
                    var pairMsg = new FramePairMessage(
                            decodedFramesMessage.videoId(), originalVideoPath, targetOutputDirectory, prevFrame, currFrame, false, originalFps, width, height, totalTargetFrames
                    );
                    ctx.fireFrameRead(pairMsg);
                }
                prevFrame = currFrame;
            }

            if (prevFrame != null) {
                var eofMsg = new FramePairMessage(
                       decodedFramesMessage.videoId(), originalVideoPath, targetOutputDirectory, prevFrame, null, true, originalFps, width, height, totalTargetFrames
                );
                ctx.fireFrameRead(eofMsg);
            }

            grabber.stop();
        } catch (Exception e) {
            throw new RuntimeException("Decoder 提取底层视频流异常", e);
        }
    }
}