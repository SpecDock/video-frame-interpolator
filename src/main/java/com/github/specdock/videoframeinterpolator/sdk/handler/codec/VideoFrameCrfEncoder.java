package com.github.specdock.videoframeinterpolator.sdk.handler.codec;

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
 * 异步状态机视频编码器 (CRF-ULTRA 高兼容极致版 - 物理参数硬锁死)
 * 1. 物理层：锁定 YUV 4:2:0 采样，结合色彩空间直通，确保跨平台播放无色偏。
 * 2. 算法层：开启 veryslow + tesa 顶级搜索，配合 CRF=16 恒定质量模式换取极致压缩比。
 * 3. 规避底层 Bug：使用底层数字 ID 强制注入 profile 和 level，彻底解决 -22 解析崩溃。
 */
public class VideoFrameCrfEncoder implements FrameHandler {

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
            throw new IllegalStateException("Encoder 状态机隔离异常：当前实例正在处理其他视频任务");
        }

        try {
            frameQueue.put(encodedMsg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void startAsyncConsumerThread(EncodedFramesMessage initMsg, FrameHandlerContext ctx) {
        Thread.ofPlatform().name("Encoder-Ultra420-Loop-" + initMsg.videoId()).start(() -> {

            Path tempVideoPath = null;
            Path finalOutputPath = null;

            try {
                // ==========================================
                // 1. 物理目录树构建 (高健壮性 NIO 解析)
                // ==========================================
                Path originalFilePath = Paths.get(initMsg.originalVideoPath());
                Path parentDirectory = originalFilePath.getParent();
                String originalFileName = originalFilePath.getFileName().toString();

                // 安全提取基名，动态适应所有格式后缀
                int dotIndex = originalFileName.lastIndexOf('.');
                String baseName = (dotIndex == -1) ? originalFileName : originalFileName.substring(0, dotIndex);

                Path targetDirectory = parentDirectory.resolve("interpolated_output");
                if (Files.notExists(targetDirectory)) {
                    Files.createDirectories(targetDirectory);
                }

                // 融入 videoId，确保多任务并发下临时文件不冲突
                String videoId = initMsg.videoId();
                String tempFileName = String.format("%s_%s_temp_videoonly_crf.mp4", baseName, videoId);
                String finalFileName = String.format("%s_%s_interpolated_crf.mp4", baseName, videoId);

                tempVideoPath = targetDirectory.resolve(tempFileName);
                finalOutputPath = targetDirectory.resolve(finalFileName);

                // ==========================================
                // 2. 元数据提取与色彩空间映射
                // ==========================================
                double originalFps;
                long originalTotalFrames;
                CodecMetadataMirror.ColorMetadata colorMetadata;

                try (var metaGrabber = createAdaptiveDecodeGrabber(initMsg.originalVideoPath())) {
                    originalFps = metaGrabber.getFrameRate();
                    originalTotalFrames = metaGrabber.getLengthInVideoFrames();
                    colorMetadata = CodecMetadataMirror.extract(metaGrabber);
                }

                long totalTargetFrames = originalFps > 0 ?
                        (long) (originalTotalFrames * (initMsg.targetFps() / originalFps)) :
                        originalTotalFrames * 2;

                // ==========================================
                // 3. 核心压制与写入逻辑
                // ==========================================
                try (var recorder = new FFmpegFrameRecorder(tempVideoPath.toString(), initMsg.width(), initMsg.height(), 0)) {
                    String codecMode = resolveCodecMode();
                    String selectedGpuEncoder = "GPU".equals(codecMode) ? resolveGpuEncoderName() : null;

                    recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
                    if (selectedGpuEncoder != null) {
                        recorder.setVideoCodecName(selectedGpuEncoder);
                    } else {
                        recorder.setVideoCodecName("libx264");
                    }
                    recorder.setFormat("mp4");
                    recorder.setFrameRate(initMsg.targetFps());
                    recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);

                    CodecMetadataMirror.apply(colorMetadata, recorder);

                    recorder.setVideoBitrate(0);
                    if (selectedGpuEncoder != null) {
                        recorder.setVideoOption("cq", "19");
                        recorder.setVideoOption("preset", "p5");
                        recorder.setVideoOption("rc", "vbr_hq");
                    } else {
                        recorder.setVideoOption("crf", "16");
                        recorder.setVideoOption("profile", "100");
                        recorder.setVideoOption("level", "62");
                        recorder.setVideoOption("preset", "veryslow");
                        recorder.setVideoOption("x264opts", "ref=16:aq-mode=3:deblock=-3,-3:me=tesa:subme=11:merange=32");
                        recorder.setVideoOption("tune", "stillimage");
                    }

                    recorder.start();
                    String activeEncoder = selectedGpuEncoder != null ? selectedGpuEncoder : "libx264";
                    System.out.println("阶段一：CRF-ULTRA 旗舰模式已启动，编解码模式=" + codecMode + "，视频编码器=" + activeEncoder);

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

                            if (processedFramesCount % 20 == 0 || processedFramesCount >= totalTargetFrames) {
                                double progress = Math.min(100.0, (processedFramesCount / (double) totalTargetFrames) * 100.0);
                                System.out.printf("\r[ULTRA-420] 编码进度: %.2f%% (%d / %d 帧)",
                                        progress, processedFramesCount, totalTargetFrames);
                                ctx.fireFrameRead(new TailFrameMessage(videoId, initMsg.originalVideoPath(), null, false, processedFramesCount, totalTargetFrames));
                            }
                        }

                        if (msg.isEof()) {
                            System.out.println("\n[ULTRA-420] 接收到 EOF 标识，正在刷新底层编码队列...");
                            break;
                        }
                    }
                    recorder.stop();
                }

                // ==========================================
                // 4. 音频复用与临时文件清理
                // ==========================================
                mergeAudioAndVideo(tempVideoPath.toString(), initMsg.originalVideoPath(), finalOutputPath.toString());
                Files.deleteIfExists(tempVideoPath);
                System.out.println("ULTRA-420 全链路处理闭环，最终视频已生成: " + finalOutputPath);
                ctx.fireFrameRead(new TailFrameMessage(videoId, initMsg.originalVideoPath(), finalOutputPath.toString(), true, totalTargetFrames, totalTargetFrames));

            } catch (Exception e) {
                System.err.println("ULTRA 异步编码线程发生致命崩溃: " + e.getMessage());
                e.printStackTrace();

                // 异常防御：发生错误时，清理可能损坏的临时文件，防止占用磁盘空间
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
                System.out.println("GPU 解码策略不可用，尝试下一个: " + hwaccel + "，原因: " + e.getMessage());
            }
        }

        FFmpegFrameGrabber fallbackGrabber = new FFmpegFrameGrabber(sourcePath);
        try {
            fallbackGrabber.start();
            System.out.println("GPU 解码策略全部回退，使用软件解码读取元数据");
            return fallbackGrabber;
        } catch (Exception e) {
            releaseGrabberQuietly(fallbackGrabber);
            e.addSuppressed(lastException);
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
                System.out.println("CRF Encoder 编解码模式已锁定: " + CODEC_MODE);
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
}
