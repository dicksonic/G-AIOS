package com.dicksonic.gaios.springai.scanner.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gaios.scanner.* 配置：安检链自动装配的全部旋钮。
 *
 * <p><b>最小可用配置（一站配齐）：</b></p>
 * <pre>
 *   gaios.scanner.models-dir=C:/models/e5          # E5 模型目录（tokenizer.json + model_quantized_base.onnx）
 * </pre>
 *
 * <p><b>全部配置项：</b></p>
 * <pre>
 *   gaios.scanner.enabled=true        # 总开关：false 时安检链与容器全扫整体关闭
 *   gaios.scanner.models-dir=         # E5 模型目录；安检链生效时必填，缺失启动 fail-fast
 *   gaios.scanner.auto-scan=true      # 启动时自动全扫容器工具并顶掉框架 ToolCallbackResolver；
 *                                     # false 时只装配安检链 bean（自行调 GaiosSecureToolRegistry 手工送检）
 * </pre>
 *
 * <p><b>仲裁模型通道（无配置项，走 bean 退位）：</b>Stage III 仲裁默认复用宿主的
 * ChatModel（启动 WARN 会打印来源并提醒标定基线）；要锁定独立仲裁通道
 * （例如钉回 deepseek-v4-flash），自行声明一个 LLMCore bean 即可整体替换，
 * 无需任何配置。</p>
 */
@ConfigurationProperties(prefix = "gaios.scanner")
public class GaiosScannerProperties {

    /** 总开关。false 时本模块所有 bean 不装配，与没引依赖等效 */
    private boolean enabled = true;

    /**
     * E5 模型文件目录（绝对路径或相对工作目录）。
     * 需要本模块装配安检链（无用户自定义 SemanticScanner）时必填；
     * 缺失或加载失败启动 fail-fast——安检链半残启动比不启动更危险。
     */
    private String modelsDir;

    /**
     * 启动时自动全扫容器工具并注册带安检的 ToolCallbackResolver（顶掉框架默认）。
     * false 时安检链 bean 照常装配，但 resolver 不接管——适合只想要
     * GaiosSecureToolRegistry 手工送检、不想让渡框架 resolver 的场景。
     */
    private boolean autoScan = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModelsDir() {
        return modelsDir;
    }

    public void setModelsDir(String modelsDir) {
        this.modelsDir = modelsDir;
    }

    public boolean isAutoScan() {
        return autoScan;
    }

    public void setAutoScan(boolean autoScan) {
        this.autoScan = autoScan;
    }
}
