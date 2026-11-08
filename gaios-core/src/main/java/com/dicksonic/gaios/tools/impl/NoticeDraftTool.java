package com.dicksonic.gaios.tools.impl;

import com.dicksonic.gaios.tools.Tool;

/**
 * 工具3：督办通知起草。
 * 关键设计：只起草、不发送。description 里写明"需人工确认后签发"——
 * 这是 L1/L2/L3 分级执行的雏形：模型只能到"草稿"，签发权在人。
 * 未来安全内核的"人工确认网关"会强制这个约束，而不是靠 description 自觉。
 */
public class NoticeDraftTool implements Tool {
    @Override
    public String name() { return "draft_notice"; }

    @Override
    public String description() {
        return "根据隐患信息起草监督督办通知草稿。参数：danger_id。仅生成草稿，需人工确认后签发。";
    }

    @Override
    public String execute(String argsJson) {
        return "【督办通知草稿】你项目存在隐患（编号见参数），请于限期内完成整改并书面回复。"
             + "（草稿，待人工审核签发）";
    }
}