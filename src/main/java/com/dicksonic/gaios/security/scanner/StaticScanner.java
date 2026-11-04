package com.dicksonic.gaios.security.scanner;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 工具描述静态扫描器。
 * 对应 MCP-Guard 论文 Stage I：轻量语法检测，毫秒级，宁可误报不可漏报的 fail-fast。
 * 职责只有一个：工具注册进 Agent 上下文之前，检查 description 是否携带明显恶意特征。
 */
public class StaticScanner {

    /** 每条规则 = 恶意特征正则 + 规则名。命中任何一条即拒绝注册 */
    private static final List<Rule> RULES = List.of(
            new Rule("SENSITIVE_PATH",  Pattern.compile("(/etc/passwd|\\.ssh|id_rsa|C:\\\\Windows)", Pattern.CASE_INSENSITIVE)),
            new Rule("EXTERNAL_URL",   Pattern.compile("https?://", Pattern.CASE_INSENSITIVE)),
            new Rule("INJECTION_TAG",  Pattern.compile("<IMPORTANT>|IMPORTANT NOTE", Pattern.CASE_INSENSITIVE)),
            new Rule("PRECHECK_TRICK", Pattern.compile("(必须先|必须首先|before any|prior to any)", Pattern.CASE_INSENSITIVE)),
            new Rule("SHELL_INJECT",   Pattern.compile("rm\\s+-rf|powershell|cmd\\.exe", Pattern.CASE_INSENSITIVE)),
            new Rule("SQL_INJECT",     Pattern.compile("(DROP\\s+TABLE|OR\\s+1=1)", Pattern.CASE_INSENSITIVE))
    );

    private record Rule(String name, Pattern pattern) {}

    /**
     * 扫描工具描述。
     * @return 命中的规则名列表；空列表 = 通过
     */
    public List<String> scan(String toolName, String description) {
        return RULES.stream()
                .filter(r -> r.pattern().matcher(description).find())
                .map(Rule::name)
                .toList();
    }
}