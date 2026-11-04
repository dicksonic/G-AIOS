package com.dicksonic.gaios.util;

import java.io.InputStream;
import java.util.Properties;

public class AppConfigUtil {
    private static final Properties props = new Properties();

    static {
        try (InputStream in = AppConfigUtil.class.getClassLoader().getResourceAsStream("app.properties")) {
            if (in == null) {
                throw new IllegalStateException("找不到 app.properties，请复制 app.properties.example 后填写");
            }
            props.load(in);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
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
}