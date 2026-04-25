package com.github.specdock.videoframeinterpolator.sdk.pipeline;

import com.github.specdock.videoframeinterpolator.sdk.context.DefaultFrameHandlerContext;
import com.github.specdock.videoframeinterpolator.sdk.context.FrameHandlerContext;
import com.github.specdock.videoframeinterpolator.sdk.handler.FrameHandler;
import com.github.specdock.videoframeinterpolator.sdk.listener.VideoProcessListener;
import com.github.specdock.videoframeinterpolator.sdk.msg.TailFrameMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author specdock
 * @Date 2026/3/25
 * @Time 19:55
 */
public class DefaultFramePipeline implements FramePipeline {
    private final HeadContext head;
    private final TailContext tail;
    private FrameHandlerContext offset;
    private VideoProcessListener listener;


    public DefaultFramePipeline() {
       head = new HeadContext(null);
       tail = new TailContext(null);
       head.next(tail);
       offset = head;
    }

    @Override
    public FramePipeline setProgressListener(VideoProcessListener listener) {
        this.listener = listener;
        tail.setListener(listener);
        return this;
    }

    @Override
    public FramePipeline addLast(FrameHandler frameHandler) {
        DefaultFrameHandlerContext ctx = new DefaultFrameHandlerContext(frameHandler, tail);
        offset.next(ctx);
        offset = ctx;
        return this;
    }

    @Override
    public FramePipeline fireFrameRead(Object msg) {
        head.fireFrameRead(msg);
        return this;
    }

    private static class HeadContext extends DefaultFrameHandlerContext implements FrameHandler {

        public HeadContext(FrameHandlerContext next) {
            super(null, next);
            handler(this);
        }

        @Override
        public void frameRead(FrameHandlerContext ctx, Object msg) {
            ctx.fireFrameRead(msg);
        }

        @Override
        public void stop(FrameHandlerContext ctx) {
            ctx.fireStop();
        }

        @Override
        public void start(FrameHandlerContext ctx) {
            ctx.fireStart();
        }
    }

    private static class TailContext extends DefaultFrameHandlerContext implements FrameHandler {
        private VideoProcessListener listener;

        public TailContext(FrameHandlerContext next) {
            super(null, next);
            handler(this);
        }

        public void setListener(VideoProcessListener listener) {
            this.listener = listener;
        }

        @Override
        public void frameRead(FrameHandlerContext ctx, Object msg) {
            if(listener == null){
                return ;
            }
            TailFrameMessage tailFrameMessage = (TailFrameMessage) msg;
            if(tailFrameMessage.isEof()){
                System.out.println("检测到视频处理完成，正在执行资源清理...");
                deleteFileSafely(tailFrameMessage.originalVideoPath());
            }

            listener.onUpdate((TailFrameMessage) msg);
        }

        public static boolean deleteFileSafely(String absolutePath) {
            // 1. 基础寻址约束校验
            if (absolutePath == null || absolutePath.trim().isEmpty()) {
                throw new IllegalArgumentException("执行删除操作的文件绝对路径不能为空");
            }

            // 2. 构造 NIO Path 实例
            Path targetPath = Paths.get(absolutePath);

            try {
                // 3. 执行系统级删除调用
                // 优势：deleteIfExists 具备幂等语义，能够安全绕过 "检查-执行" (Check-Then-Act) 的并发竞态漏洞
                return Files.deleteIfExists(targetPath);

            } catch (SecurityException e) {
                // 触发场景：当前 JVM 进程未获得目标文件系统所在目录的 Write 权限
                throw new RuntimeException("文件删除受阻，操作系统拒绝访问，请检查进程权限: " + absolutePath, e);
            } catch (IOException e) {
                // 触发场景：文件存在，但底层文件系统拒绝执行删除指令 (例如文件句柄被占用)
                throw new RuntimeException("文件删除失败，发生底层 I/O 异常，可能正被其他线程或外部进程锁定: " + absolutePath, e);
            }
        }

        @Override
        public void stop(FrameHandlerContext ctx) {
            ctx.fireStop();
        }

        @Override
        public void start(FrameHandlerContext ctx) {
            ctx.fireStart();
        }
    }
}
