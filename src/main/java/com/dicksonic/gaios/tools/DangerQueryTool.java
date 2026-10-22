package com.dicksonic.gaios.tools;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * 工具1：隐患台账查询（模拟数据版）。
 * 真实项目中这里应该对接监管平台数据库；
 * 玩票阶段硬编码，让 Agent 闭环能跑通即可。
 */
public class DangerQueryTool implements Tool {
    private static final Logger log = LogManager.getLogger(DangerQueryTool.class);

    /** 模拟隐患台账。故意放一条"逾期未整改"，方便测试 Agent 的督办场景 */
    private static final List<Map<String, String>> DANGERS = List.of(
            Map.of("id", "YD-001", "project", "某安置房项目", "content", "基坑边坡未按方案放坡",
                    "level", "较大", "deadline", "2026-10-25", "status", "未整改"),
            Map.of("id", "YD-002", "project", "某安置房项目", "content", "特种作业人员未持证上岗",
                    "level", "重大", "deadline", "2026-10-22", "status", "未整改"),
            Map.of("id", "YD-003", "project", "某产业园项目", "content", "临边防护缺失",
                    "level", "一般", "deadline", "2026-10-18", "status", "逾期未整改"));

    @Override
    public String name() { return "query_dangers"; }

    @Override
    public String description() {
        return "查询建设工程隐患整改台账。参数：status(未整改/逾期未整改，可省略)，返回隐患列表。";
    }

    @Override
    public String execute(String argsJson) {
        log.info("执行 query_dangers, args={}", argsJson);
        return DANGERS.toString();
    }
}