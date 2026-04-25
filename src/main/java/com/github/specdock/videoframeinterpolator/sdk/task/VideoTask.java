package com.github.specdock.videoframeinterpolator.sdk.task;

import com.github.specdock.videoframeinterpolator.sdk.config.GlobalConfig;
import com.github.specdock.videoframeinterpolator.sdk.handler.codec.VideoFrameCrfEncoder;
import com.github.specdock.videoframeinterpolator.sdk.handler.codec.VideoFrameDecoder;
import com.github.specdock.videoframeinterpolator.sdk.handler.core.AlgorithmConstant;
import com.github.specdock.videoframeinterpolator.sdk.handler.core.CpuAiFrameInterpolator;
import com.github.specdock.videoframeinterpolator.sdk.handler.core.GpuAiFrameInterpolator;
import com.github.specdock.videoframeinterpolator.sdk.handler.core.OpticalFlowFrameInterpolator;
import com.github.specdock.videoframeinterpolator.sdk.listener.VideoProcessListener;
import com.github.specdock.videoframeinterpolator.sdk.msg.DecodedFramesMessage;
import com.github.specdock.videoframeinterpolator.sdk.pipeline.DefaultFramePipeline;
import com.github.specdock.videoframeinterpolator.sdk.pipeline.FramePipeline;

import java.util.Objects;

/**
 * @author specdock
 * @Date 2026/3/27
 * @Time 14:30
 */
public class VideoTask implements Runnable{
    private final String videoId;
    private final String algorithmType;
    private final String originalVideoPath;
    private VideoProcessListener listener;


    public VideoTask(String videoId, String algorithmType, String originalVideoPath) {
        this.videoId = videoId;
        this.algorithmType = algorithmType;
        this.originalVideoPath = originalVideoPath;
        this.listener = null;
    }

    public void setListener(VideoProcessListener listener) {
        this.listener = listener;
    }

    @Override
    public void run() {
        DefaultFramePipeline pipeline = new DefaultFramePipeline();
        pipeline.addLast(new VideoFrameDecoder());
        if(AlgorithmConstant.CPU.equals(algorithmType)){
            pipeline.addLast(new CpuAiFrameInterpolator());
        }
        else if(AlgorithmConstant.GPU.equals(algorithmType)){
            pipeline.addLast(new GpuAiFrameInterpolator());
        }
        pipeline.addLast(new VideoFrameCrfEncoder());
        pipeline.setProgressListener(listener);
        pipeline.fireFrameRead(new DecodedFramesMessage(videoId, originalVideoPath, GlobalConfig.getInstance().getTargetOutputDirectory()));
    }

    public String getVideoId() {
        return videoId;
    }
}
