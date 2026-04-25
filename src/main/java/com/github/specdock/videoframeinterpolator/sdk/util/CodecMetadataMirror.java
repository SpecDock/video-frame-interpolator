package com.github.specdock.videoframeinterpolator.sdk.util;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;

/**
 * 编解码器元数据推断与镜像工具
 * 采用 setVideoOption 字典直通模式，绕过 JavaCV 封装 API 的版本差异。
 */
public class CodecMetadataMirror {

    public record ColorMetadata(int pixelFormat, String colorRangeMeta) {}

    /**
     * 提取源视频的色彩特征
     */
    public static ColorMetadata extract(FFmpegFrameGrabber grabber) {
        return new ColorMetadata(
                grabber.getPixelFormat(),
                grabber.getVideoMetadata("color_range")
        );
    }

    /**
     * 将色彩元数据通过字典参数硬注入 Recorder
     */
    public static void apply(ColorMetadata meta, FFmpegFrameRecorder recorder) {
        // 1. 色彩取值范围 (Color Range) 推断
        boolean isFullRange = (meta.pixelFormat() == avutil.AV_PIX_FMT_YUVJ420P ||
                meta.pixelFormat() == avutil.AV_PIX_FMT_YUVJ422P ||
                meta.pixelFormat() == avutil.AV_PIX_FMT_YUVJ444P ||
                "pc".equalsIgnoreCase(meta.colorRangeMeta()) ||
                "jpeg".equalsIgnoreCase(meta.colorRangeMeta()));

        if (isFullRange) {
            // 强制 0-255 全量程映射，防止手机录屏发灰
            recorder.setVideoOption("color_range", "pc");
        } else {
            // 维持 16-235 广播级映射
            recorder.setVideoOption("color_range", "tv");
        }

        // 2. 色彩空间矩阵标准化 (Color Space & Matrix)
        // 统一输出为 BT.709 国际高清标准，防止源视频元数据错乱导致的偏色
        recorder.setVideoOption("colorspace", "bt709");
        recorder.setVideoOption("color_primaries", "bt709");
        recorder.setVideoOption("color_trc", "bt709");
    }
}