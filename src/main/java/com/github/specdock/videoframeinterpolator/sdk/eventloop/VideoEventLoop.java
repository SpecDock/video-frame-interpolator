package com.github.specdock.videoframeinterpolator.sdk.eventloop;

import com.github.specdock.videoframeinterpolator.sdk.pipeline.DefaultFramePipeline;
import com.github.specdock.videoframeinterpolator.sdk.task.VideoTask;

import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author specdock
 * @Date 2026/3/26
 * @Time 19:50
 */
public class VideoEventLoop {
    private final Thread thread;
    private final BlockingQueue<VideoTask> taskQueue;
    public static volatile boolean running = false;

    public VideoEventLoop(){
        this.thread = new VideoEventLoopThread();
        this.taskQueue = new LinkedBlockingQueue<>(60);
    }

    public void execute(VideoTask task){
        if(!taskQueue.offer(task)){
            throw new RuntimeException("普通任务阻塞队列已经满了");
        }
    }

    public boolean remove(String videoId){
        return taskQueue.removeIf(task -> task.getVideoId().equals(videoId));
    }

    private VideoTask getTask(){
        // 从任务队列中获取任务
        try {
            return taskQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void stop(){
        System.out.println("stop");
        synchronized (VideoEventLoop.class) {
            running = false;
        }
    }

    public void start(){
        System.out.println("start");
        if(!thread.isAlive()){
            thread.start();
        }
        synchronized (VideoEventLoop.class) {
            running = true;
            VideoEventLoop.class.notifyAll();
        }
    }







    private class VideoEventLoopThread extends Thread{
        @Override
        public void run() {
            while (true){
                // 处理事件循环
                VideoTask task = getTask();
                if (task != null) {
                    task.run();
                }
            }
        }

    }
}
