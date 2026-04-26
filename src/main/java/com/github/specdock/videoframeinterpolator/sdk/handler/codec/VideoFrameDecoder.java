package com.github.specdock.videoframeinterpolator.sdk.handler.codec;

import com.github.specdock.videoframeinterpolator.sdk.context.FrameHandlerContext;
import com.github.specdock.videoframeinterpolator.sdk.eventloop.VideoEventLoop;
import com.github.specdock.videoframeinterpolator.sdk.handler.FrameHandler;
import com.github.specdock.videoframeinterpolator.sdk.msg.DecodedFramesMessage;
import com.github.specdock.videoframeinterpolator.sdk.msg.FramePairMessage;
import com.github.specdock.videoframeinterpolator.sdk.util.HardwareProbeUtility;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

/**
 * @author specdock
 *
 * 异步状态机视频解码器 (原生物理编码直通版)
 * 彻底废弃 BufferedImage，采用底层 Frame (AVFrame) 物理内存流转，杜绝 YUV->BGR 色度降采样损耗。
 */
public class VideoFrameDecoder implements FrameHandler {
    private static final String[] WINDOWS_GPU_HWACCEL_PRIORITY = {"d3d11va", "dxva2", "cuda"};
    private static final Object CODEC_MODE_LOCK = new Object();
    private static volatile String CODEC_MODE = "";

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
        try (var grabber = createAdaptiveGrabber(originalVideoPath)) {
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

    private FFmpegFrameGrabber createAdaptiveGrabber(String sourcePath) throws Exception {
        if (!isGpuMode()) {
            FFmpegFrameGrabber cpuGrabber = new FFmpegFrameGrabber(sourcePath);
            cpuGrabber.start();
            System.out.println("Decoder 使用 CPU 软解码");
            return cpuGrabber;
        }

        Exception lastException = null;
        for (String hwaccel : WINDOWS_GPU_HWACCEL_PRIORITY) {
            FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(sourcePath);
            try {
                grabber.setOption("hwaccel", hwaccel);
                if ("cuda".equals(hwaccel)) {
                    grabber.setOption("hwaccel_output_format", "cuda");
                }
                grabber.start();
                System.out.println("Decoder 使用 GPU 解码策略: " + hwaccel);
                return grabber;
            } catch (Exception e) {
                lastException = e;
                releaseGrabberQuietly(grabber);
            }
        }

        FFmpegFrameGrabber fallbackGrabber = new FFmpegFrameGrabber(sourcePath);
        try {
            fallbackGrabber.start();
            System.out.println("GPU 解码不可用，Decoder 回退到 CPU");
            return fallbackGrabber;
        } catch (Exception e) {
            releaseGrabberQuietly(fallbackGrabber);
            if (lastException != null) {
                e.addSuppressed(lastException);
            }
            throw e;
        }
    }

    private boolean isGpuMode() {
        return "GPU".equals(resolveCodecMode());
    }

    private static String resolveCodecMode() {
        if (!CODEC_MODE.isEmpty()) {
            return CODEC_MODE;
        }

        synchronized (CODEC_MODE_LOCK) {
            if (CODEC_MODE.isEmpty()) {
                boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
                CODEC_MODE = (isWindows && HardwareProbeUtility.isGpuAvailable()) ? "GPU" : "CPU";
                System.out.println("Decoder 编解码模式已锁定: " + CODEC_MODE);
            }
            return CODEC_MODE;
        }
    }

    private void releaseGrabberQuietly(FFmpegFrameGrabber grabber) {
        try {
            grabber.stop();
        } catch (Exception ignored) {
        }
        try {
            grabber.release();
        } catch (Exception ignored) {
        }
    }
}