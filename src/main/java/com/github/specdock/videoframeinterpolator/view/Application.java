package com.github.specdock.videoframeinterpolator.view;

/**
 * JVM 启动入口（欺骗启动类）
 * 用于绕过 Java 11+ 对 JavaFX 的强模块化检查 (JPMS)。
 * 此类绝不能继承 javafx.application.Application。
 *
 * @author 您的名字
 */
public class Application { // 建议重命名为 MainAppLauncher

    public static void main(String[] args) {
        // 委托给真正的 JavaFX 启动类
        AppStater.main(args);
    }
}