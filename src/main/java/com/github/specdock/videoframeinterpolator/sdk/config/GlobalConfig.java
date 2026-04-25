package com.github.specdock.videoframeinterpolator.sdk.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.InputStream;
import java.nio.file.Paths;

/**
 * 全局配置单例类
 * 采用懒汉式（双重检查锁定）实现，保证线程安全与延迟加载。
 *
 * @author specdock
 * @Date 2026/3/27
 */
public class GlobalConfig {

    private static final String DEFAULT_CONFIG_PATH = "config/config.yml";

    // 使用 volatile 保证多线程环境下的可见性与禁止指令重排序，防止获取到半初始化的对象
    private static volatile GlobalConfig instance;

    // 核心配置变量
    private String originalInputDirectory;
    private String targetOutputDirectory;

    /**
     * 私有构造函数，阻断外部直接实例化。
     * 在实例化时同步执行配置文件加载与赋值。
     */
    private GlobalConfig() {
        loadConfig();
    }

    /**
     * 获取全局配置单例对象
     *
     * @return GlobalConfig 实例
     */
    public static GlobalConfig getInstance() {
        if (instance == null) {
            synchronized (GlobalConfig.class) {
                if (instance == null) {
                    instance = new GlobalConfig();
                }
            }
        }
        return instance;
    }

    /**
     * 读取 YAML 文件并映射到内部变量
     */
    private void loadConfig() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(DEFAULT_CONFIG_PATH)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("配置文件未找到，请检查路径: " + DEFAULT_CONFIG_PATH);
            }

            JsonNode rootNode = mapper.readTree(inputStream);
            JsonNode targetNode = rootNode.path("video-frame-interpolator");

            // 1. 提取配置文件中的相对路径 (例如: "storyge" 或 "storage")
            String rawInputPath = targetNode.path("originalInputDirectory").asText("");
            String rawOutputPath = targetNode.path("targetOutputDirectory").asText("");

            // 2. 获取当前 EXE 运行时的根目录
            String baseDir = System.getProperty("user.dir");

            // 3. 路径规范化：将相对路径转换为当前环境的绝对物理路径，供全局调用
            // 使用 normalize() 消除潜在的 "./" 或 "../" 冗余结构
            this.originalInputDirectory = Paths.get(baseDir, rawInputPath).normalize().toAbsolutePath().toString();
            this.targetOutputDirectory = Paths.get(baseDir, rawOutputPath).normalize().toAbsolutePath().toString();

        } catch (Exception e) {
            throw new RuntimeException("初始化 GlobalConfig 失败，请检查配置文件格式及路径", e);
        }
    }

    // --- Getters ---

    public String getOriginalInputDirectory() {
        return originalInputDirectory;
    }

    public String getTargetOutputDirectory() {
        return targetOutputDirectory;
    }
}