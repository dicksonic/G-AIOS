package com.dicksonic.gaios.tools;


/**
 * 工具接口，刻意模仿 MCP 协议的形状。
 * 注意：入参出参都是 JSON 字符串而不是 Java 对象，
 * 因为真实的 Agent 协议里，工具调用要跨进程/跨网络传输，
 * 提前用字符串隔离，D4 做 MCP 协议层时不用改接口。
 */
public interface Tool {
    /** 工具的唯一标识，模型通过这个名字发起调用 */
    String name();
    /** 工具的自然语言描述。未来这就是 MCPTox 投毒攻击的位置 */
    /**
     * 工具的自然语言描述。
     * 警告：这段文字会被原样注入模型的 system prompt，
     * 未来 MCPTox 工具投毒攻击污染的就是这个字段。
     */
    String description();
    /** 入参出参都用 JSON 字符串，模拟 MCP 的文本协议 */
    /** 执行工具。argsJson 为模型生成的 JSON 参数字符串，返回值会回喂给模型 */
    String execute(String argsJson);
}