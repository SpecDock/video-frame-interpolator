package com.github.specdock.videoframeinterpolator.sdk.handler.codec;

import com.github.specdock.videoframeinterpolator.sdk.config.GlobalConfig;
import com.github.specdock.videoframeinterpolator.sdk.context.FrameHandlerContext;
import com.github.specdock.videoframeinterpolator.sdk.handler.FrameHandler;
import com.github.specdock.videoframeinterpolator.sdk.msg.EncodedFramesMessage;
import com.github.specdock.videoframeinterpolator.sdk.msg.TailFrameMessage;
import com.github.specdock.videoframeinterpolator.sdk.util.CodecMetadataMirror;
import com.github.specdock.videoframeinterpolator.sdk.util.HardwareProbeUtility;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author specdock
 *
 * 异步状态机视频编码器 (全链路 Native 直通与全适配版)
 * 彻底剔除 JVM 堆内图像对象，接收底层 Frame 物理指针直接落盘。
 * 【重构重点】：引入 VBV 码率平滑机制，限制瞬时峰值，锁定 YUV420P 兼容格式，并实现色彩空间直通防止发灰。
 */
public class VideoFrameAbrEncoder implements FrameHandler {

    private static final String[] WINDOWS_GPU_HWACCEL_PRIORITY = {"d3d11va", "dxva2", "cuda"};
    private static final String[] WINDOWS_GPU_ENCODER_PRIORITY = {"h264_nvenc", "h264_qsv", "h264_amf"};
    private static final Object CODEC_MODE_LOCK = new Object();
    private static volatile String CODEC_MODE = "";

    private volatile String currentVideoId = null;
    private final BlockingQueue<EncodedFramesMessage> frameQueue = new LinkedBlockingQueue<>(50);


    @Override
    public void stop(FrameHandlerContext ctx) {
        ctx.fireStop();

    }

    @Override
    public void start(FrameHandlerContext ctx) {
        ctx.fireStart();
    }


    @Override
    public void frameRead(FrameHandlerContext ctx, Object msg) {
        if (!(msg instanceof EncodedFramesMessage encodedMsg)) {
            return;
        }

        if (currentVideoId == null) {
            synchronized (this) {
                if (currentVideoId == null) {
                    this.currentVideoId = encodedMsg.videoId();
                    startAsyncConsumerThread(encodedMsg, ctx);
                }
            }
        }

        if (!currentVideoId.equals(encodedMsg.videoId())) {
            throw new IllegalStateException("Encoder 状态机异常：当前实例正在处理其他视频的帧");
        }

        try {
            frameQueue.put(encodedMsg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }


    }


    private void startAsyncConsumerThread(EncodedFramesMessage initMsg, FrameHandlerContext ctx) {
        Thread.ofPlatform().name("Encoder-EventLoop-" + initMsg.videoId()).start(() -> {

            Path tempVideoPath = null;
            Path finalOutputPath = null;

            try {
                // ==========================================
                // 1. 物理目录树构建 (重构后的高健壮性 NIO 解析)
                // ==========================================
                Path originalFilePath = Paths.get(initMsg.originalVideoPath());
                Path parentDirectory = Paths.get(GlobalConfig.getInstance().getTargetOutputDirectory());
                String originalFileName = originalFilePath.getFileName().toString();

                // 安全提取基名，剥离任何类型的扩展名
                int dotIndex = originalFileName.lastIndexOf('.');
                String baseName = (dotIndex == -1) ? originalFileName : originalFileName.substring(0, dotIndex);

                // 构造目标输出目录并前置校验
                Path targetDirectory = parentDirectory.resolve("interpolated_output");
                if (Files.notExists(targetDirectory)) {
                    Files.createDirectories(targetDirectory);
                }

                // 模板化构建文件名，动态融入 videoId
                String videoId = initMsg.videoId();
                String tempFileName = String.format("%s_%s_temp_videoonly_abr.mp4", baseName, videoId);
                String finalFileName = String.format("%s_%s_interpolated_abr.mp4", baseName, videoId);

                // 生成最终绝对路径对象
                tempVideoPath = targetDirectory.resolve(tempFileName);
                finalOutputPath = targetDirectory.resolve(finalFileName);


                // ==========================================
                // 核心模块 A：无侵入式原生元数据提取
                // ==========================================
                int originalVideoCodec;
                int originalVideoBitrate;
                double originalFps;
                long originalTotalFrames;

                CodecMetadataMirror.ColorMetadata colorMetadata;

                try (var metaGrabber = createAdaptiveDecodeGrabber(initMsg.originalVideoPath())) {
                    originalVideoCodec = metaGrabber.getVideoCodec();
                    originalVideoBitrate = metaGrabber.getVideoBitrate();
                    originalFps = metaGrabber.getFrameRate();
                    originalTotalFrames = metaGrabber.getLengthInVideoFrames();

                    colorMetadata = CodecMetadataMirror.extract(metaGrabber);
                }

                long totalTargetFrames = originalFps > 0 ?
                        (long) (originalTotalFrames * (initMsg.targetFps() / originalFps)) :
                        originalTotalFrames * 2;

                // 2. 阶段一：纯视频高保真压制
                try (var recorder = new FFmpegFrameRecorder(tempVideoPath.toString(), initMsg.width(), initMsg.height(), 0)) {
                    String codecMode = resolveCodecMode();
                    String selectedGpuEncoder = "GPU".equals(codecMode) ? resolveGpuEncoderName() : null;

                    // ==========================================
                    // 核心模块 B：全量映射参数与码率突发限制
                    // ==========================================
                    if (selectedGpuEncoder != null) {
                        recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                        recorder.setVideoCodecName(selectedGpuEncoder);
                    } else {
                        recorder.setVideoCodec(originalVideoCodec);
                    }
                    recorder.setFormat("mp4");
                    recorder.setFrameRate(initMsg.targetFps());
                    recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);

                    CodecMetadataMirror.apply(colorMetadata, recorder);

                    // ABR 码率控制与 VBV 限制
                    if (originalVideoBitrate > 0 && originalFps > 0) {
                        double scaleRatio = initMsg.targetFps() / originalFps;
                        int targetBitrate = (int) (originalVideoBitrate * scaleRatio);
                        recorder.setVideoBitrate(targetBitrate);

                        recorder.setVideoOption("maxrate", String.valueOf((int) (targetBitrate * 1.3)));
                        recorder.setVideoOption("bufsize", String.valueOf(targetBitrate * 2));
                    } else {
                        recorder.setVideoBitrate(15_000_000);
                        recorder.setVideoOption("maxrate", "18000000");
                        recorder.setVideoOption("bufsize", "30000000");
                    }

                    if (selectedGpuEncoder != null) {
                        recorder.setVideoOption("preset", "p5");
                        recorder.setVideoOption("rc", "vbr");
                    } else {
                        recorder.setVideoOption("preset", "slow");
                        recorder.setVideoOption("tune", "animation");
                    }
                    recorder.start();
                    String activeEncoder = selectedGpuEncoder != null ? selectedGpuEncoder : String.valueOf(originalVideoCodec);
                    System.out.println("阶段一：ABR 限制压制线程已启动，编解码模式=" + codecMode + "，视频编码器=" + activeEncoder + "，输出文件: " + tempVideoPath.getFileName());

                    long frameIntervalUs = (long) (1_000_000.0 / initMsg.targetFps());
                    long currentVideoPtsUs = 0;
                    long processedFramesCount = 0;

                    while (true) {
                        EncodedFramesMessage msg = frameQueue.take();

                        for (Frame img : msg.framesToEncode()) {
                            recorder.setTimestamp(currentVideoPtsUs);
                            recorder.record(img);

                            currentVideoPtsUs += frameIntervalUs;
                            processedFramesCount++;

                            img.close();

                            if (processedFramesCount % 10 == 0 || processedFramesCount >= totalTargetFrames) {
                                double progress = Math.min(100.0, (processedFramesCount / (double) totalTargetFrames) * 100.0);
                                System.out.printf("\r[阶段一-ABR] 编码进度: %.2f%% (%d / %d 帧)",
                                        progress, processedFramesCount, totalTargetFrames);
                                ctx.fireFrameRead(new TailFrameMessage(videoId, initMsg.originalVideoPath(), null, false, processedFramesCount, totalTargetFrames));
                            }
                        }

                        if (msg.isEof()) {
                            System.out.println("\n[阶段一] 接收到 EOF 标识，正在闭环视频流...");
                            break;
                        }
                    }

                    recorder.stop();
                }

                // 3. 阶段二：无损音视频流复用合并
                mergeAudioAndVideo(tempVideoPath.toString(), initMsg.originalVideoPath(), finalOutputPath.toString());

                // 4. 清理临时文件
                Files.deleteIfExists(tempVideoPath);
                System.out.println("处理全链路完成，最终高帧率视频已生成: " + finalOutputPath);
                ctx.fireFrameRead(new TailFrameMessage(videoId, initMsg.originalVideoPath(), finalOutputPath.toString(), true, totalTargetFrames, totalTargetFrames));

            } catch (Exception e) {
                System.err.println("异步编码器线程发生致命错误: " + e.getMessage());
                e.printStackTrace();

                try {
                    if (tempVideoPath != null) {
                        Files.deleteIfExists(tempVideoPath);
                    }
                    if (finalOutputPath != null) {
                        Files.deleteIfExists(finalOutputPath);
                    }
                } catch (Exception deleteEx) {
                    System.err.println("残留文件清理失败: " + deleteEx.getMessage());
                }
            } finally {
                currentVideoId = null;
                frameQueue.clear();
            }
        });
    }

    private void mergeAudioAndVideo(String videoOnlyPath, String originalVideoPath, String finalOutputPath) throws Exception {
        String ffmpegExecutable = Loader.load(org.bytedeco.ffmpeg.ffmpeg.class);

        ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpegExecutable, "-y", "-i", videoOnlyPath, "-i", originalVideoPath,
                "-c:v", "copy", "-c:a", "copy", "-map", "0:v:0", "-map", "1:a:0?", "-shortest", finalOutputPath
        );

        processBuilder.inheritIO();
        if (processBuilder.start().waitFor() != 0) {
            throw new RuntimeException("底层 FFmpeg 合并进程执行异常");
        }
    }

    private FFmpegFrameGrabber createAdaptiveDecodeGrabber(String sourcePath) throws Exception {
        if (!"GPU".equals(resolveCodecMode())) {
            FFmpegFrameGrabber cpuGrabber = new FFmpegFrameGrabber(sourcePath);
            cpuGrabber.start();
            System.out.println("元数据读取使用 CPU 软解码");
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
                System.out.println("元数据读取启用 GPU 解码策略: " + hwaccel);
                return grabber;
            } catch (Exception e) {
                lastException = e;
                releaseGrabberQuietly(grabber);
            }
        }

        FFmpegFrameGrabber fallbackGrabber = new FFmpegFrameGrabber(sourcePath);
        try {
            fallbackGrabber.start();
            System.out.println("GPU 解码策略全部回退，使用软件解码读取元数据");
            return fallbackGrabber;
        } catch (Exception e) {
            releaseGrabberQuietly(fallbackGrabber);
            if (lastException != null) {
                e.addSuppressed(lastException);
            }
            throw e;
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

    private static String resolveCodecMode() {
        if (!CODEC_MODE.isEmpty()) {
            return CODEC_MODE;
        }

        synchronized (CODEC_MODE_LOCK) {
            if (CODEC_MODE.isEmpty()) {
                boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
                CODEC_MODE = (isWindows && HardwareProbeUtility.isGpuAvailable()) ? "GPU" : "CPU";
                System.out.println("ABR Encoder 编解码模式已锁定: " + CODEC_MODE);
            }
            return CODEC_MODE;
        }
    }

    private String resolveGpuEncoderName() {
        try {
            String ffmpegExecutable = Loader.load(org.bytedeco.ffmpeg.ffmpeg.class);
            ProcessBuilder processBuilder = new ProcessBuilder(ffmpegExecutable, "-hide_banner", "-encoders");
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            process.waitFor();

            String encoderOutput = output.toString();
            for (String encoder : WINDOWS_GPU_ENCODER_PRIORITY) {
                if (encoderOutput.contains(encoder)) {
                    return encoder;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}