package com.github.specdock.videoframeinterpolator.sdk.handler.core;

import com.github.specdock.videoframeinterpolator.sdk.context.FrameHandlerContext;
import com.github.specdock.videoframeinterpolator.sdk.handler.FrameHandler;
import com.github.specdock.videoframeinterpolator.sdk.msg.EncodedFramesMessage;
import com.github.specdock.videoframeinterpolator.sdk.msg.FramePairMessage;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_video;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Scalar;

import java.util.List;

/**
 * @author specdock
 *
 * 异步状态机视频插帧器 (物理内存直通重构版)
 * 剥离 JVM 堆内 BufferedImage 依赖，实现全链路 Native Frame 零拷贝流转。
 */
public class OpticalFlowFrameInterpolator implements FrameHandler {
    // 提前分配 Native 内存，实现单线程环境下的物理内存池化
    private final Mat gray1 = new Mat();
    private final Mat gray2 = new Mat();
    private final Mat flow = new Mat();
    private final Mat mapX = new Mat();
    private final Mat mapY = new Mat();
    private final Mat interpolatedMat = new Mat();
    private final Scalar borderValue = new Scalar(0, 0, 0, 0);


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
        if (!(msg instanceof FramePairMessage pair)) {
            ctx.fireFrameRead(msg);
            return;
        }

        double targetFps = pair.originalFps() * 2;

        if (pair.isEof()) {
            // 【边界处理】：如果是最后一帧，不需要插帧，直接将其透传至编码器以闭环视频
            var outMsg = new EncodedFramesMessage(
                    pair.videoId(), pair.originalVideoPath(), pair.targetOutputDirectory(), List.of(pair.frame1()),
                    true, targetFps, pair.width(), pair.height()
            );
            ctx.fireFrameRead(outMsg);
            return;
        }

        // 正常插帧处理：利用 PointerScope 隔离 JNI 瞬时对象的内存域
        try (PointerScope scope = new PointerScope();
             OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat()) {

            Frame intermediateFrame = generateIntermediateFrame(
                    pair.frame1(), pair.frame2(), matConverter
            );

            // 组装逻辑：将前置原帧与生成的插值帧打包向下游传递
            var outMsg = new EncodedFramesMessage(
                    pair.videoId(), pair.originalVideoPath(), pair.targetOutputDirectory(), List.of(pair.frame1(), intermediateFrame),
                    false, targetFps, pair.width(), pair.height()
            );

            ctx.fireFrameRead(outMsg);
        }
    }

    /**
     * 底层 C++ 矩阵计算子程序
     * 严格复用类成员变量中的 Native 共享矩阵，避免 OOM 异常
     */
    private Frame generateIntermediateFrame(
            Frame f1, Frame f2, OpenCVFrameConverter.ToMat matConverter) {

        // 1. 将底层的 Frame 物理指针直接映射为 C++ 管理的 Mat 结构 (O(1) 复杂度，无数据拷贝)
        Mat mat1 = matConverter.convert(f1);
        Mat mat2 = matConverter.convert(f2);

        // 2. 色彩空间降维 (转换为单通道灰度图，适配 Farneback 算法)
        // 底层会复用实例变量 gray1 和 gray2 的内存块
        opencv_imgproc.cvtColor(mat1, gray1, opencv_imgproc.COLOR_BGR2GRAY);
        opencv_imgproc.cvtColor(mat2, gray2, opencv_imgproc.COLOR_BGR2GRAY);

        // 3. 调用 Farneback 算法计算稠密光流，覆盖写入实例变量 flow 矩阵
        opencv_video.calcOpticalFlowFarneback(
                gray1, gray2, flow,
                0.5, 3, 15, 3, 5, 1.2, 0
        );

        int cols = mat1.cols();
        int rows = mat1.rows();

        // 4. 初始化重映射矩阵的大小 (尺寸一致时，create 底层操作为 O(1))
        mapX.create(rows, cols, opencv_core.CV_32FC1);
        mapY.create(rows, cols, opencv_core.CV_32FC1);

        // 5. 基于光流场矩阵构建重映射坐标系
        try (FloatIndexer flowIdx = flow.createIndexer();
             FloatIndexer mapXIdx = mapX.createIndexer();
             FloatIndexer mapYIdx = mapY.createIndexer()) {

            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    // 提取当前坐标的 X 轴与 Y 轴运动偏移量
                    float dx = flowIdx.get(y, x, 0);
                    float dy = flowIdx.get(y, x, 1);

                    // 逆向映射坐标系：时间步长为 0.5 (正中间帧)
                    mapXIdx.put(y, x, x - 0.5f * dx);
                    mapYIdx.put(y, x, y - 0.5f * dy);
                }
            }
        }

        // 6. 执行双线性插值重映射，扭曲原图像素以生成补偿帧，覆盖写入 interpolatedMat
        opencv_imgproc.remap(
                mat1, interpolatedMat, mapX, mapY,
                opencv_imgproc.INTER_LINEAR, opencv_core.BORDER_CONSTANT, borderValue
        );

        // 7. 【关键内存屏障】：将类级别的共享 Mat 转换为独立的 Frame 并强制执行 clone()。
        // 这是为了在堆外开辟独立物理内存空间，防止在下游 Encoder 尚未完成 I/O 写盘前，
        // interpolatedMat 被下一次光流循环覆写，导致多线程数据串流。
        return matConverter.convert(interpolatedMat).clone();
    }
}