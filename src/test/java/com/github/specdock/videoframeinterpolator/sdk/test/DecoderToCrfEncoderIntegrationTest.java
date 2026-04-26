package com.github.specdock.videoframeinterpolator.sdk.test;

import com.github.specdock.videoframeinterpolator.sdk.context.FrameHandlerContext;
import com.github.specdock.videoframeinterpolator.sdk.eventloop.VideoEventLoop;
import com.github.specdock.videoframeinterpolator.sdk.handler.FrameHandler;
import com.github.specdock.videoframeinterpolator.sdk.handler.codec.VideoFrameCrfEncoder;
import com.github.specdock.videoframeinterpolator.sdk.handler.codec.VideoFrameDecoder;
import com.github.specdock.videoframeinterpolator.sdk.msg.DecodedFramesMessage;
import com.github.specdock.videoframeinterpolator.sdk.msg.EncodedFramesMessage;
import com.github.specdock.videoframeinterpolator.sdk.msg.FramePairMessage;
import com.github.specdock.videoframeinterpolator.sdk.msg.TailFrameMessage;
import org.bytedeco.javacv.Frame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 手工集成测试：VideoFrameDecoder -> VideoFrameCrfEncoder。
 *
 * 使用方式：
 * 1) 先填写 INPUT_VIDEO_PATH。
 * 2) 运行 main 方法。
 */
public class DecoderToCrfEncoderIntegrationTest {

    private static final String INPUT_VIDEO_PATH = "C:\\Users\\29287\\Desktop\\4月24日.mp4";
    private static final long TIMEOUT_SECONDS = 600;

    public static void main(String[] args) throws Exception {
        if (INPUT_VIDEO_PATH == null || INPUT_VIDEO_PATH.isBlank()) {
            throw new IllegalArgumentException("请先在 INPUT_VIDEO_PATH 填入视频绝对路径");
        }

        Path inputPath = Path.of(INPUT_VIDEO_PATH);
        if (!Files.exists(inputPath)) {
            throw new IllegalArgumentException("输入视频不存在: " + INPUT_VIDEO_PATH);
        }

        String videoId = "test-" + UUID.randomUUID();
        CountDownLatch doneLatch = new CountDownLatch(1);
        AtomicReference<String> outputPathRef = new AtomicReference<>(null);

        VideoFrameDecoder decoder = new VideoFrameDecoder();
        FramePairToEncodedBridgeHandler bridgeHandler = new FramePairToEncodedBridgeHandler();
        VideoFrameCrfEncoder encoder = new VideoFrameCrfEncoder();
        TailCollectHandler tailCollectHandler = new TailCollectHandler(doneLatch, outputPathRef);

        TestContext decoderCtx = new TestContext(decoder);
        TestContext bridgeCtx = new TestContext(bridgeHandler);
        TestContext encoderCtx = new TestContext(encoder);
        TestContext tailCtx = new TestContext(tailCollectHandler);

        decoderCtx.next(bridgeCtx);
        bridgeCtx.next(encoderCtx);
        encoderCtx.next(tailCtx);

        synchronized (VideoEventLoop.class) {
            VideoEventLoop.running = true;
            VideoEventLoop.class.notifyAll();
        }

        decoder.frameRead(decoderCtx, new DecodedFramesMessage(videoId, INPUT_VIDEO_PATH, ""));

        boolean finished = doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            throw new IllegalStateException("等待编码完成超时，超时时间(秒): " + TIMEOUT_SECONDS);
        }

        String outputPath = outputPathRef.get();
        if (outputPath == null || outputPath.isBlank()) {
            throw new IllegalStateException("编码已结束，但未收到输出文件路径");
        }

        Path finalVideo = Path.of(outputPath);
        if (!Files.exists(finalVideo)) {
            throw new IllegalStateException("编码完成消息已收到，但输出文件不存在: " + outputPath);
        }

        System.out.println("测试通过，输出文件: " + outputPath);
    }

    private static final class FramePairToEncodedBridgeHandler implements FrameHandler {
        @Override
        public void frameRead(FrameHandlerContext ctx, Object msg) {
            if (!(msg instanceof FramePairMessage pairMsg)) {
                ctx.fireFrameRead(msg);
                return;
            }

            if (pairMsg.frame1() == null) {
                return;
            }

            // 这里做最小链路桥接：不做插帧，只把 Decoder 产出的帧转成 Encoder 入参。
            List<Frame> toEncode = new ArrayList<>(1);
            toEncode.add(pairMsg.frame1());

            double targetFps = pairMsg.originalFps() > 0 ? pairMsg.originalFps() : 30.0;
            EncodedFramesMessage encodedMsg = new EncodedFramesMessage(
                    pairMsg.videoId(),
                    pairMsg.originalVideoPath(),
                    pairMsg.targetOutputDirectory(),
                    toEncode,
                    pairMsg.isEof(),
                    targetFps,
                    pairMsg.width(),
                    pairMsg.height()
            );
            ctx.fireFrameRead(encodedMsg);
        }

        @Override
        public void stop(FrameHandlerContext ctx) {
            ctx.fireStop();
        }

        @Override
        public void start(FrameHandlerContext ctx) {
            ctx.fireStart();
        }
    }

    private static final class TailCollectHandler implements FrameHandler {
        private final CountDownLatch doneLatch;
        private final AtomicReference<String> outputPathRef;

        private TailCollectHandler(CountDownLatch doneLatch, AtomicReference<String> outputPathRef) {
            this.doneLatch = doneLatch;
            this.outputPathRef = outputPathRef;
        }

        @Override
        public void frameRead(FrameHandlerContext ctx, Object msg) {
            if (msg instanceof TailFrameMessage tailFrameMessage) {
                if (tailFrameMessage.isEof()) {
                    outputPathRef.set(tailFrameMessage.targetOutputVideoPath());
                    doneLatch.countDown();
                }
                return;
            }
            System.out.println("TailCollectHandler 收到非 TailFrameMessage 消息: " + msg.getClass().getName());
        }

        @Override
        public void stop(FrameHandlerContext ctx) {
            // no-op
        }

        @Override
        public void start(FrameHandlerContext ctx) {
            // no-op
        }
    }

    private static final class TestContext implements FrameHandlerContext {
        private FrameHandler handler;
        private FrameHandlerContext next;

        private TestContext(FrameHandler handler) {
            this.handler = handler;
        }

        @Override
        public FrameHandler handler() {
            return handler;
        }

        @Override
        public FrameHandlerContext handler(FrameHandler handler) {
            this.handler = handler;
            return this;
        }

        @Override
        public FrameHandlerContext next() {
            return next;
        }

        @Override
        public FrameHandlerContext next(FrameHandlerContext ctx) {
            this.next = ctx;
            return this;
        }

        @Override
        public FrameHandlerContext fireFrameRead(Object msg) {
            if (next != null && next.handler() != null) {
                next.handler().frameRead(next, msg);
            }
            return this;
        }

        @Override
        public FrameHandlerContext fireStop() {
            if (next != null && next.handler() != null) {
                next.handler().stop(next);
            }
            return this;
        }

        @Override
        public FrameHandlerContext fireStart() {
            if (next != null && next.handler() != null) {
                next.handler().start(next);
            }
            return this;
        }
    }
}

