package com.dicksonic.gaios.security.scanner;

import com.dicksonic.gaios.security.scanner.enums.ScanVerdict;

/**
 * 语义安检引擎（Stage II/III）。
 * 三态判定：UNKNOWN 表示"没判成"，不是"判了安全"——处置策略由调用方决定。
 */
public interface SemanticScanner {

    /**
     * 判定工具描述是否包含投毒意图。
     * @return SAFE 放行 / MALICIOUS 隔离 / UNKNOWN 判定失败（注册期 fail-closed，运行期 fail-open）
     */
    ScanVerdict judge(String toolName, String description);

    String engineName();
}