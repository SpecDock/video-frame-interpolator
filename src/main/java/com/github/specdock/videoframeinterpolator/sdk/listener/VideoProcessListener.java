package com.github.specdock.videoframeinterpolator.sdk.listener;

import com.github.specdock.videoframeinterpolator.sdk.msg.TailFrameMessage;

/**
 * @author 29287
 */ // 2. 回调监听器接口
public interface VideoProcessListener {
    void onUpdate(TailFrameMessage update);
}