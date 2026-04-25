package com.github.specdock.videoframeinterpolator.engine;

import com.github.specdock.videoframeinterpolator.sdk.listener.VideoProcessListener;
import com.github.specdock.videoframeinterpolator.sdk.msg.TailFrameMessage;

/**
 * @author 29287
 */ // 3. 核心引擎接口 (前端直接调用此接口的实现类)
public interface VideoProcessorEngine {
    /**
     * 加入插帧队列
     * @param algorithm 算法常量 ("CPU" 或 "GPU")
     * @param inputPath 原始文件路径
     * @return 任务唯一 ID
     */
    String addVideo(String algorithm, String inputPath);

    /**
     * 删除队列中指定元素
     */
    boolean removeVideo(String id);

    /**
     * 启动处理队列
     */
    boolean start();

    /**
     * 停止处理队列
     */
    boolean stop();

    /**
     * 注册回调监听器
     */
    void setListener(VideoProcessListener listener);
}