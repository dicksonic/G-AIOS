package com.dicksonic.gaios.tools;

/**
 * 工具2：规范条款查询（模拟版）。
 * 未来 D5 做 RAG 时，这个类会被替换成"向量库检索 + 引用定位"的真实实现，
 * 届时 description 里要加上"返回结果带条文出处"，防止模型编造规范。
 */
public class RegulationQueryTool implements Tool {
    @Override
    public String name() { return "query_regulations"; }

    @Override
    public String description() {
        return "查询建设工程质量安全规范条款。参数：keyword，返回相关条文。";
    }

    @Override
    public String execute(String argsJson) {
        return "《建筑施工安全检查标准》JGJ59-2011：3.2.3 基坑支护应符合设计要求；"
             + "《危险性较大的分部分项工程安全管理规定》(住建部令第37号)：专项施工方案应经专家论证。";
    }
}