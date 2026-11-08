package com.dicksonic.gaios.llm.vision;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次图片识别请求：单图 + 提示词。
 * 设计取舍：第一版只支持本地文件（M2 拍板：图片取数先用本地目录模拟）；
 * 后续接业务系统附件读取接口时如需 URL 直传，再加形态，不预先抽象。
 */
public record VisionRequest(Path image, String prompt) {

    public VisionRequest {
        Objects.requireNonNull(image, "image 不能为空");
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
    }
}