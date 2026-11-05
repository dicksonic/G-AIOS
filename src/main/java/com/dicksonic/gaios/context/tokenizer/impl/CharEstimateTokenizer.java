package com.dicksonic.gaios.context.tokenizer.impl;

import com.dicksonic.gaios.context.tokenizer.Tokenizer;

/**
 * 字符估算 Tokenizer（先手写，后引库）。
 *
 * 取值依据：
 * - ASCII：OpenAI 官方经验值约 4 字符/token；
 * - 非 ASCII（中文为主）：主流 BPE（cl100k / DeepSeek）常用汉字约 1 token/字，
 *   故按 1 计。对中文政务文本整体偏保守（略高估），符合"宁早勿晚触发压缩"的方向。
 *
 * 失效条件：
 * - prompt 中代码 / JSON / 英文占比显著升高时偏差变大；
 * - 引入 jtokkit 后本类应被替换，仅留作对照。
 */
public class CharEstimateTokenizer implements Tokenizer {

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) {
                ascii++;
            } else {
                nonAscii++;
            }
        }
        return (int) Math.ceil(ascii / 4.0 + nonAscii);
    }
}