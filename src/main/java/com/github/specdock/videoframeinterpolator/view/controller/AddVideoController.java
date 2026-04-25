package com.github.specdock.videoframeinterpolator.view.controller;

import com.github.specdock.videoframeinterpolator.sdk.util.HardwareProbeUtility;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;

/**
 * @author 29287
 */
public class AddVideoController {

    @FXML private RadioButton gpuRadio;
    @FXML private StackPane fileDropArea;
    @FXML private Label filePathLabel;

    private String selectedFilePath = null;
    private MainController mainController;

    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    // 处理点击选择文件
    @FXML
    private void handleFileSelect() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Video Files", "*.mp4", "*.avi"));
        File file = fileChooser.showOpenDialog(fileDropArea.getScene().getWindow());
        if (file != null) {
            selectedFilePath = file.getAbsolutePath();
            filePathLabel.setText(file.getName());
        }
    }

    // 允许拖拽文件进入区域
    @FXML
    private void handleDragOver(DragEvent event) {
        Dragboard db = event.getDragboard();
        if (db.hasFiles()) {
            event.acceptTransferModes(TransferMode.COPY);
        } else {
            event.consume();
        }
    }

    // 处理文件放下动作
    @FXML
    private void handleDragDropped(DragEvent event) {
        Dragboard db = event.getDragboard();
        boolean success = false;
        if (db.hasFiles()) {
            File file = db.getFiles().get(0); // 取第一个文件
            selectedFilePath = file.getAbsolutePath();
            filePathLabel.setText(file.getName());
            success = true;
        }
        event.setDropCompleted(success);
        event.consume();
    }

    @FXML
    private void submitAdd() {
        // 1. 业务参数完整性校验
        if (selectedFilePath == null) {
            showSystemAlert(Alert.AlertType.WARNING, "参数缺失", "请先选择需要处理的视频流文件。");
            return;
        }

        boolean useGpu = gpuRadio.isSelected();

        // 2. 硬件拓扑环境前置校验 (拦截无效的 GPU 任务下发)
        if (useGpu && !HardwareProbeUtility.isGpuAvailable()) {
            System.out.println("GPU 设备缺失");
            showSystemAlert(Alert.AlertType.ERROR, "硬件环境异常", "系统未探测到有效的 GPU 设备或底层驱动缺失，请切换至 CPU 降级模式。");
            // 强制重置视图状态
            gpuRadio.setSelected(false);
            return;
        }

        // 3. 组装调度指令
        String algorithm = useGpu ? "GPU" : "CPU";

        // 触发主控制器任务流转 (RPC/事件总线调用)
        mainController.submitNewTask(algorithm, selectedFilePath);

        // 4. 销毁当前视图上下文
        Stage stage = (Stage) fileDropArea.getScene().getWindow();
        stage.close();
    }

    /**
     * 封装标准的系统级阻塞式警告窗 (Modal Dialog)
     *
     * @param alertType 弹窗级别 (ERROR, WARNING, INFO)
     * @param title     主标题
     * @param content   详细错误堆栈或提示信息
     */
    private void showSystemAlert(Alert.AlertType alertType, String title, String content) {
        Alert alert = new Alert(alertType);
        alert.setTitle(title);
        // 禁用头部区域以保持极简 UI 风格
        alert.setHeaderText(null);
        alert.setContentText(content);
        // 阻塞当前 UI 线程，等待用户确认
        alert.showAndWait();
    }
}