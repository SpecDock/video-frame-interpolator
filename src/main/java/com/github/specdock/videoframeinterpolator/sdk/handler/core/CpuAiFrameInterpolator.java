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

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
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
 * 异步状态机视频插帧器 (CPU 系统内存感知与多核并发调度版)
 */
public class CpuAiFrameInterpolator implements FrameHandler {

    // ==========================================
    // 物理层配置与探针参数 (CPU 架构适配)
    // ==========================================

    private static final String BASE_DIR = System.getProperty("user.dir");

    private static final String ENGINE_DIR_PATH = Paths.get(
            BASE_DIR, "develop", "rife-ncnn-vulkan-20221029-windows"
    ).toString();

    private static final String RIFE_ENGINE_PATH = Paths.get(
            ENGINE_DIR_PATH, "rife-ncnn-vulkan.exe"
    ).toString();

    private static final String MODEL_NAME = "rife-v4.6";

    // 系统可用物理内存安全阈值 (取空闲部分的 60%，防范系统级 OOM 及 Swap 抖动)
    private static final double FREE_RAM_USAGE_LIMIT_RATIO = 0.60;

    // CPU 推理模式下单像素的经验内存占用常数 (Bytes，通常高于 GPU 的 FP16，因需 FP32 计算或对齐)
    private static final double RIFE_CPU_BYTES_PER_PIXEL = 8.0;

    // 引擎初始化基础内存开销 (约 500MB，CPU 加载模型到主存相对占用更大)
    private static final int ENGINE_BASE_RAM_MB = 500;

    // 动态获取当前系统的可用逻辑核心数，用于 NCNN 线程池调度
    private static final int LOGICAL_CORES = Runtime.getRuntime().availableProcessors();

    // 状态机变量
    private int currentBatchSize = -1;
    private final List<FramePairMessage> frameBuffer = new ArrayList<>(30);

    // 线程独占的隔离 I/O 沙箱
    private final Path inputDir;
    private final Path outputDir;

    public CpuAiFrameInterpolator() {
        try {
            Path baseTemp = Files.createTempDirectory("cpu_ai_pipeline_");
            this.inputDir = Files.createDirectories(baseTemp.resolve("input"));
            this.outputDir = Files.createDirectories(baseTemp.resolve("output"));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteDirectoryRecursively(baseTemp.toFile())));
        } catch (Exception e) {
            throw new RuntimeException("初始化 CPU 批处理 I/O 隔离沙箱失败", e);
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

            currentBatchSize = -1;
            return;
        }

        frameBuffer.add(pair);

        if (frameBuffer.size() >= currentBatchSize) {
            flushBuffer(ctx, targetFps);
        }
    }

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

            // NCNN 线程池配置策略：加载线程:计算线程:保存线程
            // CPU 计算密集型任务，将绝大部分核心分配给计算线程
            String threadConfig = String.format("1:%d:1", Math.max(1, LOGICAL_CORES - 2));

            // 执行底层 C++ 引擎 (配置 CPU 降级与并发参数)
            ProcessBuilder processBuilder = new ProcessBuilder(
                    RIFE_ENGINE_PATH,
                    "-i", inputDir.toString(),
                    "-o", outputDir.toString(),
                    "-m", MODEL_NAME,
                    "-g", "-1",            // -1 指示 NCNN 绕过 Vulkan 显卡设备，强制降级至 CPU 运算
                    "-j", threadConfig,    // 动态映射物理多核线程并发
                    "-x"
            );

            processBuilder.directory(new File(ENGINE_DIR_PATH));
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("CPU 引擎发生致命错误 (Exit Code: " + exitCode + ")。可能触发了主存溢出或被 OS 强制 Kill。");
            }

            File[] outArray = outputDir.toFile().listFiles();
            if (outArray == null || outArray.length == 0) {
                throw new RuntimeException("CPU 引擎执行成功，但未捕获到预测数据输出");
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
     * 基于操作系统级物理内存与 CPU 计算耗时的二维弹性调度模型
     */
    private void initializeDynamicHardwareBatchSize(int width, int height) {
        long pixelCount = (long) width * height;

        // CPU 推理耗时呈指数级增加，基准 Batch 需大幅下调，防止单次 I/O 阻塞导致线程饥饿
        int baselineBatch = (pixelCount >= 3_600_000) ? 5 : 15;
        long freeRamMB = probeSystemFreeMemoryMB();

        if (freeRamMB > 0) {
            double availableRamMB = freeRamMB * FREE_RAM_USAGE_LIMIT_RATIO;
            double ramPerFrameMB = (pixelCount * RIFE_CPU_BYTES_PER_PIXEL) / (1024.0 * 1024.0);

            double usableRamForBatchMB = availableRamMB - ENGINE_BASE_RAM_MB;
            int maxSafeBatchByRam = (int) (usableRamForBatchMB / ramPerFrameMB);

            if (maxSafeBatchByRam <= 0) {
                System.out.println("[CPU-AI] 警告：系统可用主存处于极度濒危状态！强制启动最低生命维持策略 (BatchSize = 1)");
                this.currentBatchSize = 1;
                return;
            }

            this.currentBatchSize = Math.min(baselineBatch, maxSafeBatchByRam);

            System.out.printf("[CPU-AI] 环境探针: 当前系统完全空闲物理内存 %d MB，授权分配限额 %.1f MB (60%%)。\n", freeRamMB, availableRamMB);
            System.out.printf("[CPU-AI] 硬件拓扑: 探测到 %d 个逻辑核心，分配计算并发线程数: %d。\n", LOGICAL_CORES, Math.max(1, LOGICAL_CORES - 2));
            System.out.printf("[CPU-AI] 内存模型: 单帧预估增量 %.2f MB，当前余量理论支持最大 Batch = %d。\n", ramPerFrameMB, maxSafeBatchByRam);
            System.out.printf("[CPU-AI] 状态机同步: 最终弹性分配 BatchSize = %d。\n", this.currentBatchSize);

        } else {
            this.currentBatchSize = baselineBatch;
            System.out.println("[CPU-AI] 探针静默：无法获取系统内存指标，退化为默认分辨率模型 (BatchSize = " + baselineBatch + ")。");
        }
    }

    /**
     * 通过 JMX 获取操作系统级真实物理空闲内存
     */
    private long probeSystemFreeMemoryMB() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                return sunOsBean.getFreeMemorySize() / (1024 * 1024);
            }
        } catch (Exception e) {
            // 异常隔离域：降级处理
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