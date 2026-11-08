package com.dicksonic.gaios.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AppConfigUtil {

    private static final Logger log = LogManager.getLogger(AppConfigUtil.class);

    private AppConfigUtil(){}
    private static final Properties props = new Properties();

    static {
        try {
            // 优先级：工作目录外部文件 > classpath 资源。
            // 取值依据：密钥只进工作目录的 app.properties（gitignore），mvn install 打出的 jar 只含
            //           classpath 资源，密钥由此不随 jar 进入依赖方工程；
            //           classpath 兜底仅为兼容旧习惯，过渡完成后可删。
            // 失效条件：容器化部署时改为环境变量/挂载卷注入，此优先级链整体替换。
            Path external = Path.of("properties/app.properties");
            if (Files.exists(external)) {
                try (InputStream in = Files.newInputStream(external)) {
                    props.load(in);
                }
                log.info("[AppConfig] 配置来源：工作目录 {}", external.toAbsolutePath());
            } else {
                try (InputStream in = AppConfigUtil.class.getClassLoader().getResourceAsStream("app.properties")) {
                    if (in == null) {
                        throw new IllegalStateException(
                                "找不到 app.properties：工作目录与 classpath 均无，请复制 app.properties.example 后填写");
                    }
                    props.load(in);
                }
                log.info("[AppConfig] 配置来源：classpath（注意：此方式密钥会被打进 jar，仅供过渡）");
            }
        } catch (Exception e) {
            log.error(e.getMessage(),e);
        }
    }

    public static String get(String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("app.properties 缺少配置项: " + key);
        }
        return value.trim();
    }
    /** 读取浮点配置。缺省或格式错误时返回默认值，不让配置错误炸启动 */
    public static float getFloat(String key, float defaultValue) {
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 读取字符串配置，缺省或空白时返回默认值（仅供有合理默认值的非密钥配置项使用；密钥仍走 get 强制配置） */
    public static String getOrDefault(String key, String defaultValue) {
        String v = props.getProperty(key);
        return (v == null || v.isBlank()) ? defaultValue : v.trim();
    }
}