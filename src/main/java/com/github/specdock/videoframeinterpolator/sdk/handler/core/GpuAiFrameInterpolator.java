package com.github.specdock.videoframeinterpolator.sdk.handler.core;

import com.github.specdock.videoframeinterpolator.sdk.context.FrameHandlerContext;
import com.github.specdock.videoframeinterpolator.sdk.handler.FrameHandler;
import com.github.specdock.videoframeinterpolator.sdk.msg.EncodedFramesMessage;
import com.github.specdock.videoframeinterpolator.sdk.msg.FramePairMessage;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * @author specdock
 *
 * 异步状态机视频插帧器 (GPU 动态可用显存感知与弹性调度版)
 */
public class GpuAiFrameInterpolator implements FrameHandler {

    // ==========================================
    // 物理层配置与探针参数 (动态上下文寻址重构)
    // ==========================================

    // 获取当前 EXE 可执行文件所在的根目录
    private static final String BASE_DIR = System.getProperty("user.dir");

    // 动态拼接底层 AI 引擎工作目录
    private static final String ENGINE_DIR_PATH = Paths.get(
            BASE_DIR, "develop", "rife-ncnn-vulkan-20221029-windows"
    ).toString();

    // 动态拼接 AI 引擎可执行文件路径
    private static final String RIFE_ENGINE_PATH = Paths.get(
            ENGINE_DIR_PATH, "rife-ncnn-vulkan.exe"
    ).toString();

    private static final String MODEL_NAME = "rife-v4.6";

    // 动态可用显存安全红线阈值 (取空闲部分的 70%)
    private static final double FREE_VRAM_USAGE_LIMIT_RATIO = 0.70;

    // RIFE NCNN FP16 模式下单像素的经验显存占用常数 (Bytes)
    private static final double RIFE_FP16_BYTES_PER_PIXEL = 6.5;

    // Vulkan 引擎初始化基础显存开销 (约 300MB)
    private static final int ENGINE_BASE_VRAM_MB = 300;

    // 状态机变量
    private int currentBatchSize = -1;
    private final List<FramePairMessage> frameBuffer = new ArrayList<>(60);

    // 线程独占的隔离 I/O 沙箱
    private final Path inputDir;
    private final Path outputDir;

    public GpuAiFrameInterpolator() {
        try {
            Path baseTemp = Files.createTempDirectory("gpu_ai_pipeline_");
            this.inputDir = Files.createDirectories(baseTemp.resolve("input"));
            this.outputDir = Files.createDirectories(baseTemp.resolve("output"));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteDirectoryRecursively(baseTemp.toFile())));
        } catch (Exception e) {
            throw new RuntimeException("初始化 GPU 批处理 I/O 隔离沙箱失败", e);
        }
    }

    @Override
    public void start(FrameHandlerContext ctx) {
        ctx.fireStart();
    }

    @Override
    public void stop(FrameHandlerContext ctx) {
        ctx.fireStop();
    }

    @Override
    public void frameRead(FrameHandlerContext ctx, Object msg) {
        if (!(msg instanceof FramePairMessage pair)) {
            ctx.fireFrameRead(msg);
            return;
        }

        double targetFps = pair.originalFps() * 2;

        // 首次接收数据，触发动态显存探针
        if (currentBatchSize == -1) {
            initializeDynamicHardwareBatchSize(pair.width(), pair.height());
        }

        if (pair.isEof()) {
            flushBuffer(ctx, targetFps);

            var eofOutMsg = new EncodedFramesMessage(
                    pair.videoId(), pair.originalVideoPath(), pair.targetOutputDirectory(), List.of(pair.frame1()),
                    true, targetFps, pair.width(), pair.height()
            );
            ctx.fireFrameRead(eofOutMsg);

            // 释放探针锁，每个新视频任务重新评估一次显存状况
            currentBatchSize = -1;
            return;
        }

        frameBuffer.add(pair);

        if (frameBuffer.size() >= currentBatchSize) {
            flushBuffer(ctx, targetFps);
        }
    }

    /**
     * 核心算力调度枢纽：执行跨进程的 I/O 批处理并组装 DTO 下发
     */
    private void flushBuffer(FrameHandlerContext ctx, double targetFps) {
        if (frameBuffer.isEmpty()) {
            return;
        }

        int n = frameBuffer.size();

        try (PointerScope scope = new PointerScope();
             OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat()) {

            for (int i = 0; i < n; i++) {
                Mat m = matConverter.convert(frameBuffer.get(i).frame1());
                opencv_imgcodecs.imwrite(inputDir.resolve(String.format("%08d.png", i)).toString(), m);
            }
            Mat lastBoundaryMat = matConverter.convert(frameBuffer.get(n - 1).frame2());
            opencv_imgcodecs.imwrite(inputDir.resolve(String.format("%08d.png", n)).toString(), lastBoundaryMat);

            // 执行底层 C++ 引擎
            ProcessBuilder processBuilder = new ProcessBuilder(
                    RIFE_ENGINE_PATH,
                    "-i", inputDir.toString(),
                    "-o", outputDir.toString(),
                    "-m", MODEL_NAME,
                    "-g", "0",
                    "-j", "1:1:1",
                    "-x"
            );

            // 指定子进程的工作目录 (Working Directory)
            processBuilder.directory(new File(ENGINE_DIR_PATH));
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("GPU 引擎发生致命错误 (Exit Code: " + exitCode + ")。可能触发了系统级 OOM。");
            }

            File[] outArray = outputDir.toFile().listFiles();
            if (outArray == null || outArray.length == 0) {
                throw new RuntimeException("GPU 引擎执行成功，但未捕获到预测数据输出");
            }

            List<File> sortedOutFiles = Arrays.stream(outArray)
                    .sorted(Comparator.comparing(File::getName))
                    .toList();

            for (int i = 0; i < n; i++) {
                FramePairMessage originalMsg = frameBuffer.get(i);
                File interpFile = sortedOutFiles.get(2 * i + 1);

                Mat interpMat = opencv_imgcodecs.imread(interpFile.getAbsolutePath());
                Frame interpFrame = matConverter.convert(interpMat).clone();
                interpMat.release();

                var outMsg = new EncodedFramesMessage(
                        originalMsg.videoId(),
                        originalMsg.originalVideoPath(),
                        originalMsg.targetOutputDirectory(),
                        List.of(originalMsg.frame1(), interpFrame),
                        false,
                        targetFps,
                        originalMsg.width(),
                        originalMsg.height()
                );
                ctx.fireFrameRead(outMsg);
            }

        } catch (Exception e) {
            throw new RuntimeException("微批处理阶段发生致命流转故障", e);
        } finally {
            frameBuffer.clear();
            cleanDirectoryFiles(inputDir.toFile());
            cleanDirectoryFiles(outputDir.toFile());
        }
    }

    /**
     * 【核心算法】基于实时剩余显存的弹性调度模型
     */
    private void initializeDynamicHardwareBatchSize(int width, int height) {
        long pixelCount = (long) width * height;

        int baselineBatch = (pixelCount >= 3_600_000) ? 20 : 60;
        int freeVramMB = probeNvidiaFreeVram();

        if (freeVramMB > 0) {
            double availableVramMB = freeVramMB * FREE_VRAM_USAGE_LIMIT_RATIO;
            double vramPerFrameMB = (pixelCount * RIFE_FP16_BYTES_PER_PIXEL) / (1024.0 * 1024.0);

            double usableVramForBatchMB = availableVramMB - ENGINE_BASE_VRAM_MB;
            int maxSafeBatchByVram = (int) (usableVramForBatchMB / vramPerFrameMB);

            if (maxSafeBatchByVram <= 0) {
                System.out.println("[GPU-AI] 警告：系统可用显存处于极度濒危状态！强制启动最低生命维持策略 (BatchSize = 2)");
                this.currentBatchSize = 2;
                return;
            }

            this.currentBatchSize = Math.min(baselineBatch, maxSafeBatchByVram);

            System.out.printf("[GPU-AI] 环境探针: 当前系统完全空闲显存 %d MB，授权分配限额 %.1f MB (70%%)。\n", freeVramMB, availableVramMB);
            System.out.printf("[GPU-AI] 内存模型: 单帧预估增量 %.2f MB，当前余量理论支持最大 Batch = %d。\n", vramPerFrameMB, maxSafeBatchByVram);
            System.out.printf("[GPU-AI] 状态机同步: 最终弹性分配 BatchSize = %d。\n", this.currentBatchSize);

        } else {
            this.currentBatchSize = baselineBatch;
            System.out.println("[GPU-AI] 探针静默：未检测到有效 NVIDIA 驱动支持，退化为默认分辨率模型 (BatchSize = " + baselineBatch + ")。");
        }
    }

    private int probeNvidiaFreeVram() {
        try {
            ProcessBuilder pb = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits");
            Process p = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    return Integer.parseInt(line.trim());
                }
            }
            p.waitFor();
        } catch (Exception e) {
            // 异常隔离域：防止环境依赖缺失污染主业务流
        }
        return -1;
    }

    private void cleanDirectoryFiles(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    private void deleteDirectoryRecursively(File dir) {
        File[] allContents = dir.listFiles();
        if (allContents != null) {
            for (File file : allContents) {
                deleteDirectoryRecursively(file);
            }
        }
        dir.delete();
    }
}