package com.dicksonic.gaios;

import com.dicksonic.gaios.tools.DangerQueryTool;
import com.dicksonic.gaios.tools.NoticeDraftTool;
import com.dicksonic.gaios.tools.RegulationQueryTool;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

public class App {
    private static final Logger log = LogManager.getLogger(App.class);
    //主入口
    public static void main(String[] args) {
        SimpleReactAgent agent = new SimpleReactAgent(
                new DeepSeekCore(),
                List.of(new DangerQueryTool(), new RegulationQueryTool(), new NoticeDraftTool()));

        // 一个问句覆盖三个工具，验证Agent能自主规划多步调用
        String answer = agent.run(
                "某安置房项目有哪些隐患还没整改？该依据哪条规范？帮我起草一份督办通知草稿。");
        log.info("最终回复: {}", answer);
    }
}