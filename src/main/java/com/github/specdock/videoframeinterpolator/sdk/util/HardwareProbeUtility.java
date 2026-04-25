package com.github.specdock.videoframeinterpolator.sdk.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * 增强型底层硬件拓扑探针工具 (支持 WMI 与多级容错)
 */
public class HardwareProbeUtility {

    // 探针状态缓存 (防抖动与减少系统调用开销)
    private static Boolean gpuAvailableCache = null;

    /**
     * 验证系统环境是否具备 Vulkan 兼容的 GPU 加速能力
     */
    public static boolean isGpuAvailable() {
        if (gpuAvailableCache != null) {
            return gpuAvailableCache;
        }

        // 策略一：NVIDIA 专属 SMI 探针 (获取高精度驱动状态)
        if (probeNvidiaSmi()) {
            gpuAvailableCache = true;
            return true;
        }

        // 策略二：Windows WMI 底层硬件表查探 (兼容 4060 及 AMD/Intel 核显 Vulkan 环境)
        gpuAvailableCache = probeWindowsWmi();
        return gpuAvailableCache;
    }

    /**
     * 第一级探针：尝试通过 NVIDIA System Management Interface 验证
     */
    private static boolean probeNvidiaSmi() {
        // 定义候选的可执行文件调用路径
        String[] smiCandidates = {
                "nvidia-smi", // 依赖系统 PATH 变量
                "C:\\Windows\\System32\\nvidia-smi.exe", // 标准系统目录
                "C:\\Program Files\\NVIDIA Corporation\\NVSMI\\nvidia-smi.exe" // 物理安装目录
        };

        for (String cmd : smiCandidates) {
            // 前置校验物理文件是否存在（针对绝对路径）
            if (cmd.contains(":\\") && !new File(cmd).exists()) {
                continue;
            }

            Process process = null;
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(cmd);
                processBuilder.redirectErrorStream(true);
                process = processBuilder.start();

                // 设定 2 秒 I/O 阻塞阈值
                boolean finished = process.waitFor(2, TimeUnit.SECONDS);

                if (finished && process.exitValue() == 0) {
                    return true;
                }
            } catch (Exception e) {
                // 当前指令集失效，尝试下一个候选路径
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
        return false;
    }

    /**
     * 第二级探针：通过 WMI 查询 Win32_VideoController 实体
     * 探测是否存在非 Microsoft 基础显示适配器的独立/核心显卡
     */
    private static boolean probeWindowsWmi() {
        Process process = null;
        try {
            // 调用 Windows 系统自带的 WMI 命令行工具
            ProcessBuilder processBuilder = new ProcessBuilder("wmic", "path", "win32_VideoController", "get", "name");
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "GBK"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String deviceName = line.trim().toUpperCase();
                    // 过滤无关输出，匹配主流显卡厂商标识
                    if (deviceName.contains("NVIDIA") || deviceName.contains("AMD") ||
                            deviceName.contains("RADEON") || deviceName.contains("INTEL")) {
                        return true;
                    }
                }
            }

            process.waitFor(2, TimeUnit.SECONDS);
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}