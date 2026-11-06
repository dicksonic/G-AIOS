package com.dicksonic.gaios.context.swap.impl;

import com.dicksonic.gaios.context.swap.event.SwapEvent;
import com.dicksonic.gaios.context.swap.SwapStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JSONL 文件换出存储（D7）：一行一个压缩事件，追加写。
 * 懒创建：首次 spill 才建目录和文件——零压缩零文件，不污染环境。
 * 失败只打日志不抛异常：审计层不能搞挂主流程。
 */
public class FileSwapStore implements SwapStore {

    private static final Logger log = LogManager.getLogger(FileSwapStore.class);

    /** 同 JVM 内的实例序号，仅用于文件名区分多次运行/多个 Context */
    private static final AtomicInteger INSTANCE_SEQ = new AtomicInteger();

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dir;
    private final int instanceId;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 懒创建的目标文件，首次 spill 前为 null */
    private Path file;

    /**
     * @param dir 换出文件目录（相对路径基于工作目录），不存在时首次 spill 自动创建
     */
    public FileSwapStore(Path dir) {
        this.dir = dir;
        this.instanceId = INSTANCE_SEQ.incrementAndGet();
    }

    @Override
    public synchronized void spill(SwapEvent event) {
        try {
            if (file == null) {
                Files.createDirectories(dir);
                file = dir.resolve("swap-" + LocalDateTime.now().format(FILE_TS)
                        + "-" + instanceId + ".jsonl");
                log.info("[FileSwapStore] 换出文件就绪：{}", file.toAbsolutePath());
            }
            String line = mapper.writeValueAsString(event);
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("[FileSwapStore] 换出事件已落盘：seq={}, 滑出 {} 条", event.seq(), event.messages().size());
        } catch (IOException e) {
            log.warn("[FileSwapStore] 换出落盘失败：seq={} 的原文仅存在于内存，审计链出现缺口", event.seq(), e);
        }
    }
}