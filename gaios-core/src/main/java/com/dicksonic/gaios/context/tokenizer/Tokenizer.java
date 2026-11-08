package com.dicksonic.gaios.context.tokenizer;

/**
 * Token 计量抽象（D6.1）。
 * 先上字符估算实现（CharEstimateTokenizer），接口预留；
 * 后续引入 jtokkit 等真实 tokenizer 时新增实现即可，Context 不用动。
 */
public interface Tokenizer {

    /** 估算文本的 token 数。允许高估（偏保守、提前触发压缩），不允许系统性低估。 */
    int count(String text);
}