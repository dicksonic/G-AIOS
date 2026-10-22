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
}