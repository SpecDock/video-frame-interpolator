package com.github.specdock.videoframeinterpolator.view;

import com.github.specdock.videoframeinterpolator.sdk.config.GlobalConfig;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * JavaFX 核心启动类
 * 负责 UI 生命周期的初始化、主视图加载及启动前置环境清理
 *
 * @author 29287
 */
public class AppStater extends Application {

    @Override
    public void init() throws Exception {
        // JavaFX 生命周期方法：在 start() 之前在 JavaFX Launcher 线程中执行
        // 适合在此处放置前置的非 UI 阻塞型 I/O 操作或配置加载
        cleanUpWorkspaceOnStartup();
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        String fxmlPath = "/view/fxml/MainView.fxml";
        URL resource = getClass().getResource(fxmlPath);

        if (resource == null) {
            throw new RuntimeException("无法找到 FXML 视图文件，请检查资源路径: " + fxmlPath);
        }

        FXMLLoader loader = new FXMLLoader(resource);
        Parent root = loader.load();

        Scene scene = new Scene(root, 800, 600);
        primaryStage.setTitle("视频插帧器-仅支持2倍插帧");
        primaryStage.setScene(scene);

        primaryStage.setOnCloseRequest(event -> {
            // 优雅停机钩子：释放底层资源
            System.exit(0);
        });

        primaryStage.show();
    }

    /**
     * 执行启动时的环境清理策略
     */
    private void cleanUpWorkspaceOnStartup() {
        try {
            GlobalConfig config = GlobalConfig.getInstance();

            // 策略 1：清理 originalInputDirectory，保留 interpolated_output 子目录
            String originalInputStr = config.getOriginalInputDirectory();
            if (originalInputStr != null && !originalInputStr.trim().isEmpty()) {
                Path originalDir = Paths.get(originalInputStr);
                if (Files.exists(originalDir) && Files.isDirectory(originalDir)) {
                    try (Stream<Path> paths = Files.list(originalDir)) {
                        paths.forEach(path -> {
                            // 检查是否为被保护的 interpolated_output 文件夹
                            boolean isProtectedDir = Files.isDirectory(path) &&
                                    path.getFileName().toString().equals("interpolated_output");

                            if (!isProtectedDir) {
                                deletePathRecursively(path);
                            }
                        });
                    }
                }
            }

            // 策略 2：清理 targetOutputDirectory/interpolated_output 下名为 temp_videoonly 的文件
            String targetOutputStr = config.getTargetOutputDirectory();
            if (targetOutputStr != null && !targetOutputStr.trim().isEmpty()) {
                Path targetOutputSubDir = Paths.get(targetOutputStr, "interpolated_output");
                if (Files.exists(targetOutputSubDir) && Files.isDirectory(targetOutputSubDir)) {
                    try (Stream<Path> paths = Files.list(targetOutputSubDir)) {
                        paths.forEach(path -> {
                            // 精确匹配：必须是标准文件且文件名包含 "temp_videoonly"
                            if (Files.isRegularFile(path) && path.getFileName().toString().contains("temp_videoonly")) {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    // 降级处理：不中断主流程
                                    System.err.println("清理残留临时文件失败: " + path.toAbsolutePath());
                                }
                            }
                        });
                    }
                }
            }
        } catch (Exception e) {
            // 异常隔离：启动时的环境清理属于旁路逻辑，发生异常时记录日志并保障主 UI 线程正常启动
            System.err.println("初始化清理工作空间发生严重异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 底层 I/O 辅助方法：安全地递归删除目录或文件
     *
     * @param path 目标路径（文件或文件夹）
     */
    private void deletePathRecursively(Path path) {
        try {
            if (Files.isDirectory(path)) {
                // 如果是目录，则深度优先递归清空其内部子项
                try (Stream<Path> entries = Files.list(path)) {
                    entries.forEach(this::deletePathRecursively);
                }
            }
            // 具备幂等性的原子删除
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println("底层文件系统拒绝删除访问: " + path.toAbsolutePath() + " | 原因: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}