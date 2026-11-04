package com.dicksonic.gaios.security.scanner;

import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Stage II：语义恶意检测。
 * 对应 MCP-Guard 论文中 Stage II 的职责（论文用微调的 E5 嵌入实现，
 * 我们先用 LLM 判断跑通链路——等积累了标注样本、心疼 token 成本后，
 * 再按论文换成 E5 分类器，架构不变，只换检测引擎）。
 *
 * 定位：专门抓 Stage I 正则抓不住的"语义级投毒"——
 * 没有攻击特征词，但意图是诱导 Agent 改变执行顺序、泄露数据或越权。
 */
public interface SemanticScanner {

    /** 判断工具描述是否包含投毒意图。true = 恶意，应隔离 */
    boolean isMalicious(String toolName, String description);

    /** 当前引擎名，写入日志——安全审计必须能回答"当时是谁判的" */
    String engineName();


}