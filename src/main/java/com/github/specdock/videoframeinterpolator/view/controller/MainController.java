package com.github.specdock.videoframeinterpolator.view.controller;

import com.github.specdock.videoframeinterpolator.engine.VideoFrameInterpolatorEngine;
import com.github.specdock.videoframeinterpolator.engine.VideoProcessorEngine;
import com.github.specdock.videoframeinterpolator.sdk.config.GlobalConfig;
import com.github.specdock.videoframeinterpolator.sdk.listener.VideoProcessListener;
import com.github.specdock.videoframeinterpolator.sdk.msg.TailFrameMessage;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主界面控制器
 * 负责 UI 生命周期管理与插帧引擎的回调状态同步
 * @author 29287
 */
public class MainController implements VideoProcessListener {

    @FXML private ListView<String> waitingListView;
    @FXML private ListView<String> completedListView;
    @FXML private Label currentTaskLabel;
    @FXML private ProgressBar taskProgressBar;
    @FXML private Label progressLabel;
    @FXML private Button toggleProcessBtn;
    // 【新增】成品地址复制按钮的注入
    @FXML private Button copyOutputDirBtn;

    private boolean isRunning = false;

    // 注入核心引擎的实现类
    private final VideoProcessorEngine engine = new VideoFrameInterpolatorEngine();

    // 核心状态注册表：映射 Task ID 到 绝对文件路径
    private final Map<String, String> taskPathRegistry = new ConcurrentHashMap<>();

    @FXML
    public void initialize() {
        // 1. 注册底层视频处理引擎的回调钩子
        engine.setListener(this);

        // 2. 【新增视觉特性】为两个列表渲染自定义带有“复制”按钮的单元格
        setupListViewWithCopyButton(waitingListView);
        setupListViewWithCopyButton(completedListView);

        // 3. 为等待队列绑定双击移除事件 (业务逻辑完全保持不变)
        waitingListView.setOnMouseClicked(event -> {
            // 校验：必须是鼠标左键双击
            if (event.getClickCount() == 2) {
                String selectedItem = waitingListView.getSelectionModel().getSelectedItem();

                if (selectedItem != null) {
                    String videoId = extractIdFromDisplayText(selectedItem);
                    if (videoId != null) {
                        showDeleteConfirmation(videoId, selectedItem);
                    }
                }
            }
        });
    }

    /**
     * 【新增方法】UI 渲染拦截器：为 ListView 添加前置复制按钮
     * 业务逻辑数据(String)不变，仅改变渲染表现(Graphic)
     */
    private void setupListViewWithCopyButton(ListView<String> listView) {
        listView.setCellFactory(lv -> new ListCell<String>() {
            private final HBox container = new HBox(10);
            private final Label textLabel = new Label();
            private final Button copyBtn = new Button("复制");

            {
                // 设置现代感内联样式，匹配您的浅色蓝灰主题
                copyBtn.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: #3B82F6; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 3 8; -fx-font-size: 11px;");
                textLabel.setStyle("-fx-text-fill: #475569;");

                // 布局设置
                container.setAlignment(Pos.CENTER_LEFT);
                HBox.setHgrow(textLabel, Priority.ALWAYS);
                // 按照要求：前面多一个复制按钮
                container.getChildren().addAll(copyBtn, textLabel);

                // 复制按钮点击事件
                copyBtn.setOnAction(e -> {
                    String item = getItem();
                    if (item != null) {
                        // 利用固定格式 " | 路径: " 切割出后半部分的绝对路径
                        String pathPrefix = " | 路径: ";
                        int pathIdx = item.indexOf(pathPrefix);
                        if (pathIdx != -1) {
                            String absolutePath = item.substring(pathIdx + pathPrefix.length());

                            // 写入系统剪贴板
                            Clipboard clipboard = Clipboard.getSystemClipboard();
                            ClipboardContent content = new ClipboardContent();
                            content.putString(absolutePath);
                            clipboard.setContent(content);

                            // 给予用户视觉反馈 (将按钮文字变成 √，1秒后恢复)
                            copyBtn.setText("已复制");
                            copyBtn.setStyle("-fx-background-color: #DCFCE7; -fx-text-fill: #16A34A; -fx-background-radius: 4; -fx-padding: 3 8; -fx-font-size: 11px;");

                            new Thread(() -> {
                                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                                Platform.runLater(() -> {
                                    copyBtn.setText("复制");
                                    copyBtn.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: #3B82F6; -fx-background-radius: 4; -fx-cursor: hand; -fx-padding: 3 8; -fx-font-size: 11px;");
                                });
                            }).start();
                        }
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    textLabel.setText(item); // 文本标签显示完整信息 (ID + 路径)
                    setGraphic(container);   // 将整个 HBox 设为单元格的视觉内容
                }
            }
        });
    }

    /**
     * 从 UI 展示的格式化字符串中反向提取 VideoId。
     */
    private String extractIdFromDisplayText(String displayText) {
        try {
            String prefix = "ID: ";
            String separator = " | ";

            int startIndex = displayText.indexOf(prefix) + prefix.length();
            int endIndex = displayText.indexOf(separator);

            if (startIndex >= prefix.length() && endIndex > startIndex) {
                return displayText.substring(startIndex, endIndex);
            }
        } catch (Exception e) {
            System.err.println("解析列表元素 ID 失败，脏数据介入: " + displayText);
        }
        return null;
    }

    /**
     * 弹出原生确认对话框，二次拦截用户的删除操作。
     */
    private void showDeleteConfirmation(String videoId, String selectedItem) {
        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION);
        confirmAlert.setTitle("操作确认");
        confirmAlert.setHeaderText("移出等待队列");

        confirmAlert.setContentText(String.format("确定要取消并删除该任务吗？\n\n目标数据:\n%s", selectedItem));

        confirmAlert.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                removeTask(videoId);
            }
        });
    }

    @FXML
    private void handleAddVideo() throws Exception {
        String fxmlPath = "/view/fxml/AddVideoDialog.fxml";
        java.net.URL resourceUrl = getClass().getResource(fxmlPath);

        if (resourceUrl == null) {
            throw new RuntimeException("系统严重异常：无法在类路径中定位到 FXML 资源 -> " + fxmlPath);
        }

        FXMLLoader loader = new FXMLLoader(resourceUrl);
        javafx.scene.Parent root = loader.load();

        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle("添加任务");
        stage.setScene(new Scene(root));

        AddVideoController controller = loader.getController();
        controller.setMainController(this);

        stage.showAndWait();
    }

    /**
     * 【新增方法】处理点击“复制成品地址”按钮的事件
     */
    @FXML
    private void handleCopyOutputDir() {
        String targetOutputPath = GlobalConfig.getInstance().getTargetOutputDirectory();

        if (targetOutputPath != null && !targetOutputPath.isEmpty()) {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(targetOutputPath);
            clipboard.setContent(content);

            copyOutputDirBtn.setText("已复制 ✓");
            // 【修改点】同步调小动画状态下的内边距和字号
            copyOutputDirBtn.setStyle("-fx-background-color: #DCFCE7; -fx-text-fill: #16A34A; -fx-background-radius: 6; -fx-padding: 6 14; -fx-font-size: 12px; -fx-font-weight: bold;");

            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
                Platform.runLater(() -> {
                    copyOutputDirBtn.setText("复制路径");
                    // 【修改点】同步调小恢复原样时的内边距和字号
                    copyOutputDirBtn.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: #3B82F6; -fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 6 14; -fx-font-size: 12px; -fx-font-weight: bold;");
                });
            }).start();
        } else {
            copyOutputDirBtn.setText("路径为空");
            // 【修改点】同步调小异常状态下的内边距和字号
            copyOutputDirBtn.setStyle("-fx-background-color: #FEE2E2; -fx-text-fill: #EF4444; -fx-background-radius: 6; -fx-padding: 6 14; -fx-font-size: 12px; -fx-font-weight: bold;");

            new Thread(() -> {
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                Platform.runLater(() -> {
                    copyOutputDirBtn.setText("复制路径");
                    // 【修改点】同步调小恢复原样时的内边距和字号
                    copyOutputDirBtn.setStyle("-fx-background-color: #EFF6FF; -fx-text-fill: #3B82F6; -fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 6 14; -fx-font-size: 12px; -fx-font-weight: bold;");
                });
            }).start();
        }
    }

    @FXML
    private void handleToggleProcess() {
        if (!isRunning) {
            if (engine.start()) {
                isRunning = true;
                toggleProcessBtn.setText("■ 停止");
            }
        } else {
            if (engine.stop()) {
                isRunning = false;
                toggleProcessBtn.setText("▶ 启动");
            }
        }
    }

    public void submitNewTask(String algorithm, String filePath) {
        String videoId = String.valueOf(engine.addVideo(algorithm, filePath));

        taskPathRegistry.put(videoId, filePath);

        String displayText = formatListDisplayString(videoId, filePath);
        waitingListView.getItems().add(displayText);
    }

    /**
     * 根据任务 ID 移除队列中的指定元素
     *
     * @param videoId 任务全局唯一标识
     * @return boolean 移除结果：成功返回 true，失败返回 false
     */
    public boolean removeTask(String videoId) {
        // 调用底层引擎尝试移除任务
        if (engine.removeVideo(videoId)) {
            // 移除成功：从状态注册表中弹出路径
            String filePath = taskPathRegistry.remove(videoId);

            // 如果存在该任务，从 UI 等待队列中擦除
            if (filePath != null) {
                waitingListView.getItems().remove(formatListDisplayString(videoId, filePath));
            }
            return true;
        } else {
            // 移除失败：引擎拒绝了移除请求（可能任务已进入处理状态且无法中断）
            // 弹出警告提示框反馈给用户
            Alert warningAlert = new Alert(Alert.AlertType.WARNING);
            warningAlert.setTitle("操作失败");
            warningAlert.setHeaderText(null); // 取消头部文本，使弹窗更加简约
            warningAlert.setContentText("视频插帧中无法移除");

            // 阻塞展示警告信息
            warningAlert.showAndWait();

            return false;
        }
    }

    @Override
    public void onUpdate(TailFrameMessage update) {
        final String videoId = String.valueOf(update.videoId());

        Platform.runLater(() -> {
            String filePath = taskPathRegistry.get(videoId);

            if (filePath == null) {
                return;
            }

            if (update.isEof()) {
                String displayText = formatListDisplayString(videoId, filePath);

                waitingListView.getItems().remove(displayText);
                completedListView.getItems().add(displayText);

                taskPathRegistry.remove(videoId);

                currentTaskLabel.setText("无处理任务");
                taskProgressBar.setProgress(1.0);
                progressLabel.setText("处理完成");

            } else {
                currentTaskLabel.setText(String.format("任务 [%s] 处理中...", videoId));
                double progress = (double) update.currentFrameIndex() / update.totalFrames();
                taskProgressBar.setProgress(progress);
                progressLabel.setText(update.currentFrameIndex() + " / " + update.totalFrames() + " 帧");
            }
        });
    }

    private String formatListDisplayString(String id, String path) {
        return String.format("ID: %s | 路径: %s", id, path);
    }
}