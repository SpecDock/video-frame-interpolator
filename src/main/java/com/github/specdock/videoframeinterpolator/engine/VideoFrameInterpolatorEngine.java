package com.github.specdock.videoframeinterpolator.engine;

import com.github.specdock.videoframeinterpolator.sdk.config.GlobalConfig;
import com.github.specdock.videoframeinterpolator.sdk.eventloop.VideoEventLoop;
import com.github.specdock.videoframeinterpolator.sdk.listener.VideoProcessListener;
import com.github.specdock.videoframeinterpolator.sdk.msg.TailFrameMessage;
import com.github.specdock.videoframeinterpolator.sdk.task.VideoTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * @author specdock
 * @Date 2026/3/27
 * @Time 15:11
 */
public class VideoFrameInterpolatorEngine implements VideoProcessorEngine{
    private final VideoEventLoop videoEventLoop;

    private VideoProcessListener listener;

    public VideoFrameInterpolatorEngine(){
        this.videoEventLoop = new VideoEventLoop();
    }



    @Override
    public String addVideo(String algorithm, String inputPath) {
        String videoId = UUID.randomUUID().toString();
        Path sourcePath = Paths.get(inputPath);
        String targetDirPathStr = GlobalConfig.getInstance().getOriginalInputDirectory();
        Path targetDirPath = Paths.get(targetDirPathStr);
        String originalVideoPath = null;

        try {
            // 1. 确保目标目录层级存在
            Files.createDirectories(targetDirPath);

            // 2. 解析并提取源文件扩展名 (例如: ".mp4", ".avi")
            String originalFileName = sourcePath.getFileName().toString();
            String extension = "";
            int dotIndex = originalFileName.lastIndexOf('.');

            // 健壮性校验：确保 '.' 存在，且不是文件的首字符（如 Linux 隐藏文件 ".gitignore"），且非尾字符
            if (dotIndex > 0 && dotIndex < originalFileName.length() - 1) {
                extension = originalFileName.substring(dotIndex);
            }

            // 3. 构建基于 UUID 的新文件名拼接
            String newFileName = videoId + extension;

            // 4. 解析最终的目标文件路径
            // 例如：/target/dir 拼接 123e4567-e89b-12d3-a456-426614174000.mp4
            Path targetFilePath = targetDirPath.resolve(newFileName);

            // 5. 执行文件系统拷贝与重命名动作
            Files.copy(sourcePath, targetFilePath, StandardCopyOption.REPLACE_EXISTING);

            originalVideoPath = targetFilePath.toAbsolutePath().toString();

        } catch (IOException e) {
            throw new RuntimeException("文件拷贝与重命名失败。源: " + inputPath + ", 目标: " + targetDirPathStr, e);
        }

        VideoTask task = new VideoTask(videoId, algorithm, originalVideoPath);
        task.setListener(listener);
        videoEventLoop.execute(task);

        // 建议：接口若支持，应考虑将生成的 videoId 返回给调用方，而非硬编码返回 1
        return videoId;
    }

    @Override
    public boolean removeVideo(String id) {
        return videoEventLoop.remove(id);
    }

    @Override
    public boolean start() {
        videoEventLoop.start();
        return true;
    }

    @Override
    public boolean stop() {
        videoEventLoop.stop();
        return true;
    }

    @Override
    public void setListener(VideoProcessListener listener) {
        this.listener = listener;
    }


}
