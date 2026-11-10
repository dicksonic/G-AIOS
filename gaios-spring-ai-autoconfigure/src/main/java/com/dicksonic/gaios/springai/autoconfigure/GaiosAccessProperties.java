package com.dicksonic.gaios.springai.autoconfigure;

import java.util.LinkedHashMap;
import java.util.Map;

import com.dicksonic.gaios.springai.access.ToolRiskLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审批关卡配置。
 *
 * <pre>
 * gaios:
 *   access:
 *     enabled: true            # 总开关，false 退位回框架默认 ToolCallingManager
 *     default-level: L1        # 未显式配置工具的分级（默认 L1=零行为变更起步）
 *     tool-levels:             # 工具名 → 分级
 *       query_regulations: L1
 *       draft_rectification_notice: L2
 *       delete_hazard_records: L3
 * </pre>
 */
@ConfigurationProperties(prefix = "gaios.access")
public class GaiosAccessProperties {

    /** 总开关。false 时整个审批链退位，框架默认 ToolCallingManager 接管。 */
    private boolean enabled = true;

    /** 未显式配置级别的工具按此级别处理。 */
    private ToolRiskLevel defaultLevel = ToolRiskLevel.L1;

    /** 工具名 → 风险分级。 */
    private Map<String, ToolRiskLevel> toolLevels = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ToolRiskLevel getDefaultLevel() {
        return defaultLevel;
    }

    public void setDefaultLevel(ToolRiskLevel defaultLevel) {
        this.defaultLevel = defaultLevel;
    }

    public Map<String, ToolRiskLevel> getToolLevels() {
        return toolLevels;
    }

    public void setToolLevels(Map<String, ToolRiskLevel> toolLevels) {
        this.toolLevels = toolLevels;
    }
}
